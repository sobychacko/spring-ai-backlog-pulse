package com.springai.pulse.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Releases the startup heap spike once the app is up.
 *
 * <p>On a mostly idle service the old generation may not be collected for hours, so startup
 * garbage stays committed and resident — and billed. One explicit full GC after
 * {@link ApplicationReadyEvent} lets the collector free it and (with a small
 * {@code MaxHeapFreeRatio}) uncommit the pages.
 *
 * <p>The biggest single spike — {@code TransformersEmbeddingModel} reading the ~420 MB ONNX
 * model into a {@code byte[]} before handing it to ONNX Runtime — no longer happens at boot
 * (see {@link LazyEmbeddingConfig}); it now occurs on first embedding use and is reclaimed by
 * the next full collection.
 */
@Component
public class StartupHeapTrim {

	private static final Logger log = LoggerFactory.getLogger(StartupHeapTrim.class);

	@EventListener(ApplicationReadyEvent.class)
	public void trim() {
		Runtime rt = Runtime.getRuntime();
		long before = rt.totalMemory() - rt.freeMemory();
		System.gc();
		long after = rt.totalMemory() - rt.freeMemory();
		log.info("Post-startup heap trim: used {} MB -> {} MB (committed {} MB)", before >> 20, after >> 20,
				rt.totalMemory() >> 20);
	}

}
