/*
 * Copyright 2014 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.amqp.rabbit.listener;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import org.springframework.beans.factory.BeanInitializationException;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.context.SmartLifecycle;
import org.springframework.util.Assert;

/**
 * Creates the necessary {@link MessageListenerContainer} instances for the
 * registered {@linkplain RabbitListenerEndpoint endpoints}. Also manages the
 * lifecycle of the listener containers, in particular within the lifecycle
 * of the application context.
 *
 * <p>Contrary to {@link MessageListenerContainer}s created manually, listener
 * containers managed by registry are not beans in the application context and
 * are not candidates for autowiring. Use {@link #getListenerContainers()} if
 * you need to access this registry's listener containers for management purposes.
 * If you need to access to a specific message listener container, use
 * {@link #getListenerContainer(String)} with the id of the endpoint.
 *
 * @author Stephane Nicoll
 * @author Juergen Hoeller
 * @since 1.4
 * @see RabbitListenerEndpoint
 * @see MessageListenerContainer
 * @see RabbitListenerContainerFactory
 */
public class RabbitListenerEndpointRegistry implements DisposableBean, SmartLifecycle {

	protected final Log logger = LogFactory.getLog(getClass());

	private final Map<String, MessageListenerContainer> listenerContainers =
			new LinkedHashMap<String, MessageListenerContainer>();

	private int phase = Integer.MAX_VALUE;


	/**
	 * Return the {@link MessageListenerContainer} with the specified id or
	 * {@code null} if no such container exists.
	 * @param id the id of the container
	 * @return the container or {@code null} if no container with that id exists
	 * @see RabbitListenerEndpoint#getId()
	 */
	public MessageListenerContainer getListenerContainer(String id) {
		Assert.hasText(id, "Container identifier must not be empty");
		return this.listenerContainers.get(id);
	}

	/**
	 * @return the managed {@link MessageListenerContainer} instance(s).
	 */
	public Collection<MessageListenerContainer> getListenerContainers() {
		return Collections.unmodifiableCollection(this.listenerContainers.values());
	}


	/**
	 * Create a message listener container for the given {@link RabbitListenerEndpoint}.
	 * <p>This create the necessary infrastructure to honor that endpoint
	 * with regards to its configuration.
	 * @param endpoint the endpoint to add.
	 * @param factory the {@link RabbitListenerContainerFactory} to use.
	 * @see #getListenerContainers()
	 * @see #getListenerContainer(String)
	 */
	public void registerListenerContainer(RabbitListenerEndpoint endpoint, RabbitListenerContainerFactory<?> factory) {
		Assert.notNull(endpoint, "Endpoint must not be null");
		Assert.notNull(factory, "Factory must not be null");

		String id = endpoint.getId();
		Assert.hasText(id, "Endpoint id must not be empty");
		Assert.state(!this.listenerContainers.containsKey(id),
				"Another endpoint is already registered with id '" + id + "'");

		MessageListenerContainer container = createListenerContainer(endpoint, factory);
		this.listenerContainers.put(id, container);
	}

	/**
	 * Create and start a new {@link MessageListenerContainer} using the specified factory.
	 * @param endpoint the endpoint to create a {@link MessageListenerContainer}.
	 * @param factory the {@link RabbitListenerContainerFactory} to use.
	 * @return the {@link MessageListenerContainer}.
	 */
	protected MessageListenerContainer createListenerContainer(RabbitListenerEndpoint endpoint,
			RabbitListenerContainerFactory<?> factory) {

		MessageListenerContainer listenerContainer = factory.createListenerContainer(endpoint);

		if (listenerContainer instanceof InitializingBean) {
			try {
				((InitializingBean) listenerContainer).afterPropertiesSet();
			}
			catch (Exception ex) {
				throw new BeanInitializationException("Failed to initialize message listener container", ex);
			}
		}

		int containerPhase = listenerContainer.getPhase();
		if (containerPhase < Integer.MAX_VALUE) {  // a custom phase value
			if (this.phase < Integer.MAX_VALUE && this.phase != containerPhase) {
				throw new IllegalStateException("Encountered phase mismatch between container factory definitions: " +
						this.phase + " vs " + containerPhase);
			}
			this.phase = listenerContainer.getPhase();
		}

		return listenerContainer;
	}


	@Override
	public void destroy() {
		for (MessageListenerContainer listenerContainer : getListenerContainers()) {
			if (listenerContainer instanceof DisposableBean) {
				try {
					((DisposableBean) listenerContainer).destroy();
				}
				catch (Throwable ex) {
					logger.warn("Failed to destroy message listener container", ex);
				}
			}
		}
	}


	// Delegating implementation of SmartLifecycle

	@Override
	public int getPhase() {
		return this.phase;
	}

	@Override
	public boolean isAutoStartup() {
		return true;
	}

	@Override
	public void start() {
		for (MessageListenerContainer listenerContainer : getListenerContainers()) {
			if (listenerContainer.isAutoStartup()) {
				listenerContainer.start();
			}
		}
	}

	@Override
	public void stop() {
		for (MessageListenerContainer listenerContainer : getListenerContainers()) {
			listenerContainer.stop();
		}
	}

	@Override
	public void stop(Runnable callback) {
		Collection<MessageListenerContainer> listenerContainers = getListenerContainers();
		AggregatingCallback aggregatingCallback = new AggregatingCallback(listenerContainers.size(), callback);
		for (MessageListenerContainer listenerContainer : listenerContainers) {
			listenerContainer.stop(aggregatingCallback);
		}
	}

	@Override
	public boolean isRunning() {
		for (MessageListenerContainer listenerContainer : getListenerContainers()) {
			if (listenerContainer.isRunning()) {
				return true;
			}
		}
		return false;
	}


	private static class AggregatingCallback implements Runnable {

		private final AtomicInteger count;

		private final Runnable finishCallback;

		public AggregatingCallback(int count, Runnable finishCallback) {
			this.count = new AtomicInteger(count);
			this.finishCallback = finishCallback;
		}

		@Override
		public void run() {
			if (this.count.decrementAndGet() == 0) {
				this.finishCallback.run();
			}
		}

	}

}
