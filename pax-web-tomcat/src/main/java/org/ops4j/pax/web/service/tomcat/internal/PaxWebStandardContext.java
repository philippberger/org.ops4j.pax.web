/*
 * Copyright 2020 OPS4J.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.ops4j.pax.web.service.tomcat.internal;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;
import javax.servlet.DispatcherType;
import javax.servlet.Filter;
import javax.servlet.Servlet;
import javax.servlet.ServletContainerInitializer;
import javax.servlet.ServletContext;
import javax.servlet.ServletContextListener;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpSessionAttributeListener;
import javax.servlet.http.HttpSessionBindingEvent;

import org.apache.catalina.Container;
import org.apache.catalina.LifecycleException;
import org.apache.catalina.LifecycleState;
import org.apache.catalina.core.StandardContext;
import org.apache.catalina.valves.ValveBase;
import org.apache.tomcat.util.descriptor.web.ErrorPage;
import org.ops4j.pax.web.service.WebContainerContext;
import org.ops4j.pax.web.service.spi.model.OsgiContextModel;
import org.ops4j.pax.web.service.spi.model.elements.ErrorPageModel;
import org.ops4j.pax.web.service.spi.model.elements.EventListenerKey;
import org.ops4j.pax.web.service.spi.model.elements.EventListenerModel;
import org.ops4j.pax.web.service.spi.model.elements.FilterModel;
import org.ops4j.pax.web.service.spi.model.elements.ServletModel;
import org.ops4j.pax.web.service.spi.servlet.Default404Servlet;
import org.ops4j.pax.web.service.spi.servlet.OsgiFilterChain;
import org.ops4j.pax.web.service.spi.servlet.OsgiScopedServletContext;
import org.ops4j.pax.web.service.spi.servlet.OsgiServletContext;
import org.ops4j.pax.web.service.spi.servlet.OsgiSessionAttributeListener;
import org.ops4j.pax.web.service.spi.servlet.PreprocessorFilterConfig;
import org.ops4j.pax.web.service.spi.servlet.SCIWrapper;
import org.osgi.service.http.whiteboard.Preprocessor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Extension of {@link StandardContext} that keeps track of default
 * {@link org.ops4j.pax.web.service.spi.model.OsgiContextModel} and
 * {@link javax.servlet.ServletContext} to use for chains that do not have target servlet mapped. These are
 * required by filters which may be associated with such servlet-less chains.
 */
public class PaxWebStandardContext extends StandardContext {

	public static final Logger LOG = LoggerFactory.getLogger(PaxWebStandardContext.class);

	/**
	 * Name of an attribute that indicates a {@link PaxWebStandardContext} for given request processing
	 */
	public static final String PAXWEB_STANDARD_CONTEXT = ".paxweb.standard.context";
	/**
	 * Name of an attribute that indicates a {@link PaxWebStandardWrapper} for given request processing
	 */
	public static final String PAXWEB_STANDARD_WRAPPER = ".paxweb.standard.wrapper";

	/**
	 * Default {@link ServletContext} to use for chains without target servlet (e.g., filters only)
	 */
	private OsgiServletContext defaultServletContext;
	/**
	 * Default {@link OsgiContextModel} to use for chains without target servlet (e.g., filters only)
	 */
	private OsgiContextModel defaultOsgiContextModel;
	/**
	 * Default {@link WebContainerContext} for chains without target {@link Servlet}
	 */
	private WebContainerContext defaultWebContainerContext;

	private String osgiInitFilterName;

	// TODO: these are kept, so we can replace the active context and preprocessors

	private PaxWebFilterMap osgiInitFilterMap;
	private PaxWebFilterDef osgiInitFilterDef;

	/**
	 * Highest ranked {@link OsgiServletContext} set when Tomcat's context starts
	 */
	private ServletContext osgiServletContext;

	/**
	 * {@link Preprocessor} are registered as filters, but without particular target
	 * {@link org.ops4j.pax.web.service.spi.servlet.OsgiServletContext}, so they're effectively registered in
	 * all available physical servlet contexts.
	 */
	private final List<PreprocessorFilterConfig> preprocessors = new LinkedList<>();

	private final Collection<SCIWrapper> servletContainerInitializers = new LinkedList<>();

	/**
	 * This maps keeps all the listeners in order, as expected by OSGi CMPN R7 Whiteboard specification.
	 */
	private final Map<EventListenerKey, Object> rankedListeners = new TreeMap<>();

	/**
	 * Here we'll keep the listeners without associated {@link EventListenerModel}
	 */
	private final List<Object> orderedListeners = new ArrayList<>();

	private final OsgiSessionAttributeListener osgiSessionsBridge;

	public PaxWebStandardContext(Default404Servlet defaultServlet, OsgiSessionAttributeListener osgiSessionsBridge) {
		super();
		getPipeline().addValve(new PaxWebStandardContextValve((ValveBase) getPipeline().getBasic(), defaultServlet));
		this.osgiSessionsBridge = osgiSessionsBridge;
	}

	/**
	 * Called just after creation of this {@link StandardContext} to add first filter that will handle OSGi specifics.
	 * Due to Tomcat's usage of static and final methods, it's really far from beautiful code.
	 */
	public void createInitialOsgiFilter() {
		// turn a chain into a filter - to satisfy Tomcat's static methods
		Filter osgiInitFilter = (request, response, chain) -> {
			// this is definitiely the first filter, so we should get these attributes
			PaxWebStandardContext delegate = PaxWebStandardContext.this;
			PaxWebStandardWrapper wrapper = (PaxWebStandardWrapper) request.getAttribute(PAXWEB_STANDARD_WRAPPER);
			request.removeAttribute(PAXWEB_STANDARD_WRAPPER);

			if (wrapper == null) {
				Container[] children = PaxWebStandardContext.this.findChildren();
				for (Container c : children) {
					if (c instanceof PaxWebStandardWrapper && request instanceof HttpServletRequest
							&& c.getName() != null
							&& c.getName().equals(((HttpServletRequest) request).getHttpServletMapping().getServletName())) {
						wrapper = (PaxWebStandardWrapper) c;
					}
				}
			}

			final OsgiFilterChain osgiChain;
			List<Preprocessor> preprocessorInstances = preprocessors.stream().map(PreprocessorFilterConfig::getInstance).collect(Collectors.toList());
			if (wrapper != null && !wrapper.is404()) {
				osgiChain = new OsgiFilterChain(new ArrayList<>(preprocessorInstances),
						wrapper.getServletContext(), wrapper.getWebContainerContext(), null, osgiSessionsBridge);
			} else {
				osgiChain = new OsgiFilterChain(new ArrayList<>(preprocessorInstances),
						delegate.getDefaultServletContext(), delegate.getDefaultWebContainerContext(), null, osgiSessionsBridge);
			}

			// this chain will be called (or not)
			osgiChain.setChain(chain);
			osgiChain.doFilter(request, response);
		};

		FilterModel filterModel = new FilterModel("__osgi@" + System.identityHashCode(osgiInitFilter),
				new String[] { "*" }, null, null, osgiInitFilter, null, true);
		filterModel.getMappingsPerDispatcherTypes().get(0).setDispatcherTypes(new DispatcherType[] {
				DispatcherType.ERROR,
				DispatcherType.FORWARD,
				DispatcherType.INCLUDE,
				DispatcherType.REQUEST,
				DispatcherType.ASYNC
		});
		osgiInitFilterDef = new PaxWebFilterDef(filterModel, true, null);
		osgiInitFilterMap = new PaxWebFilterMap(filterModel, true);

		addFilterDef(osgiInitFilterDef);
		addFilterMapBefore(osgiInitFilterMap);
	}

	/**
	 * This method may be called long after initial filter was created. In Jetty and Undertow there's no
	 * <em>initial</em> filter, because we can do it better, but with Tomcat we have to do it like this.
	 *
	 * @param defaultServletContext
	 */
	public void setDefaultServletContext(OsgiServletContext defaultServletContext) {
		this.defaultServletContext = defaultServletContext;
		if (defaultServletContext != null) {
			this.defaultWebContainerContext = defaultOsgiContextModel.resolveHttpContext(defaultOsgiContextModel.getOwnerBundle());
		}
	}

	/**
	 * We have to ensure that this {@link StandardContext} will always return
	 * proper instance of {@link javax.servlet.ServletContext} - especially in the events passed to listeners
	 *
	 * @param osgiServletContext
	 */
	public void setOsgiServletContext(ServletContext osgiServletContext) {
		this.osgiServletContext = osgiServletContext;
	}

	@Override
	public void addServletContainerInitializer(ServletContainerInitializer sci, Set<Class<?>> classes) {
		// we don't want initializers in Tomcat's context, because we manage them ourselves
	}

	public void setServletContainerInitializers(Collection<SCIWrapper> wrappers) {
		this.servletContainerInitializers.clear();
		this.servletContainerInitializers.addAll(wrappers);
	}

	@Override
	public ServletContext getServletContext() {
		// we have to initialize it if it's not done already
		ServletContext superContext = super.getServletContext();
		if (osgiServletContext != null) {
			return osgiServletContext;
		}
		return superContext;
	}

	@Override
	public boolean filterStart() {
		for (PreprocessorFilterConfig fc : preprocessors) {
			try {
				fc.getInstance().init(fc);
			} catch (ServletException e) {
				LOG.warn("Problem during preprocessor initialization: {}", e.getMessage(), e);
			}
		}

		return super.filterStart();
	}

	@Override
	public boolean filterStop() {
		boolean result = super.filterStop();

		// destroy the preprocessors
		for (PreprocessorFilterConfig fc : preprocessors) {
			fc.destroy();
		}

		return result;
	}

	/**
	 * Handy method to check if the context is started for OSGi purposes
	 *
	 * @return
	 */
	public boolean isStarted() {
		return getState() == LifecycleState.STARTED
				|| getState() == LifecycleState.STARTING
				|| getState() == LifecycleState.STARTING_PREP
				|| getState() == LifecycleState.INITIALIZING;
	}

	@Override
	public boolean listenerStart() {
		// This is a method overriden JUST because it is invoked right after original
		// org.apache.catalina.core.StandardContext.startInternal() invokes ServletContainerInitializers.
		// We have to call SCIs ourselves to pass better OsgiServletContext there.

		// I know listenerStart() is NOT the method which should invoke SCIs, but hey - we want to stay as consistent
		// between Jetty, Tomcat and Undertow in Pax Web as possible

		boolean ok = true;
		for (SCIWrapper wrapper : servletContainerInitializers) {
			ClassLoader tccl = Thread.currentThread().getContextClassLoader();
			try {
				Thread.currentThread().setContextClassLoader(getParentClassLoader());
				wrapper.onStartup();
			} catch (ServletException e) {
				LOG.error(sm.getString("standardContext.sciFail"), e);
				ok = false;
			} finally {
				Thread.currentThread().setContextClassLoader(tccl);
			}
		}

		if (ok) {
			// first, Tomcat doesn't have to be aware of ANY application lifecycle listeners (call this method
			// through super pointer!)
			// only when it sets us the instances of listeners (we override this method) we can start returning
			// them - that's the only way to prevent Tomcat passing org.apache.catalina.core.StandardContext.NoPluggabilityServletContext
			// to our listeners
			super.setApplicationLifecycleListeners(new Object[0]);
			return super.listenerStart();
		}

		return false;
	}

	@Override
	protected synchronized void stopInternal() throws LifecycleException {
		// org.apache.catalina.core.StandardContext.resetContext() will be call so we have to preserve some
		// items from the context
		Container[] children = findChildren();

		// this will clear the listeners, but we'll add them again when (re)starting the context
		super.stopInternal();

		for (Container child : children) {
			if (child instanceof PaxWebStandardWrapper) {
				PaxWebStandardWrapper pwsw = ((PaxWebStandardWrapper) child);
				ServletModel model = pwsw.getServletModel();
				OsgiScopedServletContext osgiServletContext = (OsgiScopedServletContext) pwsw.getServletContext();
				PaxWebStandardWrapper wrapper = new PaxWebStandardWrapper(model,
						pwsw.getOsgiContextModel(), osgiServletContext.getOsgiContext(), this);

				boolean isDefaultResourceServlet = model.isResourceServlet();
				for (String pattern : model.getUrlPatterns()) {
					isDefaultResourceServlet &= "/".equals(pattern);
				}
				if (model.isResourceServlet()) {
					wrapper.addInitParameter("pathInfoOnly", Boolean.toString(!isDefaultResourceServlet));
				}
				addChild(wrapper);

				// <servlet-mapping>
				String name = model.getName();
				for (String pattern : model.getUrlPatterns()) {
					removeServletMapping(pattern);
					addServletMappingDecoded(pattern, name, false);
				}

				// are there any error page declarations in the model?
				ErrorPageModel epm = model.getErrorPageModel();
				if (epm != null && epm.isValid()) {
					String location = epm.getLocation();
					for (String ex : epm.getExceptionClassNames()) {
						ErrorPage errorPage = new ErrorPage();
						errorPage.setExceptionType(ex);
						errorPage.setLocation(location);
						addErrorPage(errorPage);
					}
					for (int code : epm.getErrorCodes()) {
						ErrorPage errorPage = new ErrorPage();
						errorPage.setErrorCode(code);
						errorPage.setLocation(location);
						addErrorPage(errorPage);
					}
					if (epm.isXx4()) {
						for (int c = 400; c < 500; c++) {
							ErrorPage errorPage = new ErrorPage();
							errorPage.setErrorCode(c);
							errorPage.setLocation(location);
							addErrorPage(errorPage);
						}
					}
					if (epm.isXx5()) {
						for (int c = 500; c < 600; c++) {
							ErrorPage errorPage = new ErrorPage();
							errorPage.setErrorCode(c);
							errorPage.setLocation(location);
							addErrorPage(errorPage);
						}
					}
				}
			}
		}

		// clear the OSGi context - new one will be set when the context is started again
		setOsgiServletContext(null);

		// remove the listeners without associated EventListenerModel from rankedListeners map
		rankedListeners.entrySet().removeIf(e -> e.getKey().getRanklessPosition() >= 0);
		// ALL listeners added without a model (listeners added by SCIs and other listeners) will be cleared
		orderedListeners.clear();
	}

	@Override
	public void addApplicationEventListener(Object listener) {
		addApplicationEventListener(null, listener);
	}

	/**
	 * Special {@code addApplicationEventListener()} that should be called instead of
	 * {@link #addApplicationEventListener(Object)}, because we want to sort the listeners according to
	 * Whiteboard/ranking rules.
	 *
	 * @param model
	 * @param listener
	 */
	public void addApplicationEventListener(EventListenerModel model, Object listener) {
		// we're not adding the listener to StandardContext - we'll add all listeners when the context is started

		if (listener instanceof HttpSessionAttributeListener) {
			// we should not pass information about meta OSGi-scoped session
			listener = proxiedHttpSessionAttributeListener(listener);
		}

		if (model == null || model.isDynamic()) {
			orderedListeners.add(listener);
		} else {
			rankedListeners.put(EventListenerKey.ofModel(model), listener);
		}

		if (!ServletContextListener.class.isAssignableFrom(listener.getClass())) {
			// otherwise it'll be added anyway when context is started, because such listener can
			// be added only for stopped context
			if (isStarted()) {
				// we have to add it, because there'll be no restart
				super.addApplicationEventListener(listener);
			}
		}
	}

	@Override
	public void addApplicationLifecycleListener(Object listener) {
		addApplicationLifecycleListener(null, listener);
	}

	public void addApplicationLifecycleListener(EventListenerModel model, Object listener) {
		// for now, we mix lifecycle and event listeners
		addApplicationEventListener(model, listener);
	}

	/**
	 * When removing listeners, we have to remove them from managed ordered lists - whether it's lifecycle or
	 * event listener.
	 *
	 * @param listener
	 */
	public void removeListener(EventListenerModel model, Object listener) {
		if (model == null || model.isDynamic()) {
			orderedListeners.remove(listener);
		} else {
			rankedListeners.remove(EventListenerKey.ofModel(model));
		}
	}

	@Override
	public void setApplicationLifecycleListeners(Object[] listeners) {
		if (getState() == LifecycleState.STOPPING) {
			// it's null anyway
			super.setApplicationLifecycleListeners(listeners);
			return;
		}

		// when Tomcat sets here the listener instances, we'll alter the array with the instances we've collected

		// we have to prevent adding the same listeners multiple times - this may happen when Tomcat
		// context is restarted and we have a mixture of Whiteboards listeners, listeners added by SCIs and
		// listeners from other listener

		// SCIs may have added some listeners which we've hijacked, to order them according
		// to Whiteboard/ranking rules. Now it's perfect time to add them in correct order
		for (int pos = 0; pos < orderedListeners.size(); pos++) {
			Object el = orderedListeners.get(pos);
			rankedListeners.put(EventListenerKey.ofPosition(pos), el);
		}

		// Add all listeners as "pluggability listeners"
		List<Object> lifecycleListeners = new ArrayList<>();
		List<Object> eventListeners = new ArrayList<>();
		for (Object listener : rankedListeners.values()) {
			if (listener instanceof ServletContextListener) {
				lifecycleListeners.add(listener);
			}
			// because ServletContextListener's implementation may implement other listener interfaces too
			eventListeners.add(listener);
		}

		super.setApplicationLifecycleListeners(lifecycleListeners.toArray());
		super.setApplicationEventListeners(eventListeners.toArray());
	}

	public Object proxiedHttpSessionAttributeListener(Object eventListener) {
		SessionFilteringInvocationHandler handler = new SessionFilteringInvocationHandler(eventListener);
		ClassLoader cl = getParentClassLoader();
		if (cl == null) {
			// for test scenario
			cl = eventListener.getClass().getClassLoader();
		}
		Set<Class<?>> interfaces = new LinkedHashSet<>();
		Class<?> c = eventListener.getClass();
		while (c != Object.class) {
			interfaces.addAll(Arrays.asList(c.getInterfaces()));
			c = c.getSuperclass();
		}
		return Proxy.newProxyInstance(cl, interfaces.toArray(new Class[0]), handler);
	}

	public void setDefaultOsgiContextModel(OsgiContextModel defaultOsgiContextModel) {
		this.defaultOsgiContextModel = defaultOsgiContextModel;
	}

	public OsgiServletContext getDefaultServletContext() {
		return defaultServletContext;
	}

	public OsgiContextModel getDefaultOsgiContextModel() {
		return defaultOsgiContextModel;
	}

	public WebContainerContext getDefaultWebContainerContext() {
		return defaultWebContainerContext;
	}

	public List<PreprocessorFilterConfig> getPreprocessors() {
		return preprocessors;
	}

	private static class SessionFilteringInvocationHandler implements InvocationHandler {
		private final Object eventListener;

		SessionFilteringInvocationHandler(Object eventListener) {
			this.eventListener = eventListener;
		}

		@Override
		public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
			boolean proceed = method.getName().equals("attributeAdded")
					|| method.getName().equals("attributeRemoved")
					|| method.getName().equals("attributeReplaced");
			if (proceed && method.getDeclaringClass() == HttpSessionAttributeListener.class) {
				HttpSessionBindingEvent event = (HttpSessionBindingEvent) args[0];
				if (event.getName().startsWith("__osgi@session@")) {
					return null;
				}
				return method.invoke(eventListener, args);
			}
			return method.invoke(eventListener, args);
		}
	}

}
