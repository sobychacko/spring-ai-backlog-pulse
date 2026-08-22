/*
 * Copyright 2026-present the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.springai.pulse.config;

import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Defers the ONNX embedding model to first use instead of application startup.
 *
 * <p>{@code TransformersEmbeddingModel} loads the ~420 MB all-mpnet-base-v2 model into an ONNX
 * Runtime session in {@code afterPropertiesSet()}, which costs several seconds and a large heap
 * spike. None of the landing views need it — only semantic search, similar-items and the admin
 * embed/duplicate-scan flows do — so on a serverless deployment (Railway wakes the service per
 * request) eager loading puts the model on the critical path of every cold start for no benefit.
 *
 * <p>Auto-configured beans can't be annotated, so a {@link BeanFactoryPostProcessor} flips the
 * two relevant bean definitions to lazy: the model itself and {@code PgVectorStore} (whose
 * constructor takes the model, and would otherwise drag it in at startup). Matching is by
 * factory (the auto-configuration class) plus bean name, so no other bean is affected. The
 * services that inject {@code VectorStore} take it {@code @Lazy}, receiving a proxy that
 * triggers the load on first call.
 */
@Configuration(proxyBeanMethods = false)
public class LazyEmbeddingConfig {

	@Bean
	static BeanFactoryPostProcessor lazyEmbeddingBeans() {
		return (ConfigurableListableBeanFactory beanFactory) -> {
			for (String name : beanFactory.getBeanDefinitionNames()) {
				BeanDefinition bd = beanFactory.getBeanDefinition(name);
				String factory = bd.getFactoryBeanName();
				if (factory == null) {
					continue;
				}
				boolean embeddingModel = factory.contains("TransformersEmbeddingModelAutoConfiguration")
						&& "embeddingModel".equals(name);
				boolean pgVectorStore = factory.contains("PgVectorStoreAutoConfiguration")
						&& "vectorStore".equals(name);
				if (embeddingModel || pgVectorStore) {
					bd.setLazyInit(true);
				}
			}
		};
	}

}
