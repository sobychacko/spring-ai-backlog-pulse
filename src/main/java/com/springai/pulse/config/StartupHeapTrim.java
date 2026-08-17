package com.springai.pulse.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Releases the startup-only heap spike once the app is up.
 *
 * <p>Spring AI's {@code TransformersEmbeddingModel} reads the whole ONNX model (~420 MB for
 * all-mpnet-base-v2) into a {@code byte[]} on the Java heap before handing it to ONNX Runtime,
 * which keeps its own native copy. The array is garbage immediately afterwards, but on a mostly
 * idle service the old generation may not be collected for hours, so that memory stays
 * committed and resident — and billed. One explicit full GC after {@link ApplicationReadyEvent}
 * lets the collector free it and (with a small {@code MaxHeapFreeRatio}) uncommit the pages.
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
