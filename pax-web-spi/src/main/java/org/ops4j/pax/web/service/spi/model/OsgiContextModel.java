/*
 * Copyright 2007 Alin Dreghiciu.
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
package org.ops4j.pax.web.service.spi.model;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Hashtable;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.Function;
import javax.servlet.ServletContext;

import org.ops4j.pax.web.service.PaxWebConstants;
import org.ops4j.pax.web.service.WebContainerContext;
import org.ops4j.pax.web.service.spi.context.DefaultServletContextHelper;
import org.ops4j.pax.web.service.spi.context.WebContainerContextWrapper;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.Constants;
import org.osgi.framework.FrameworkUtil;
import org.osgi.framework.ServiceReference;
import org.osgi.service.http.HttpContext;
import org.osgi.service.http.context.ServletContextHelper;
import org.osgi.service.http.whiteboard.HttpWhiteboardConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <p>This class represents OSGi-specific {@link HttpContext}/{@link ServletContextHelper}
 * and points to single, server-specific {@link javax.servlet.ServletContext} and (at model level) to single
 * {@link ServletContextModel}. It maps <em>directly</em> 1:1 to an OSGi service registered by user:<ul>
 *     <li>{@link HttpContext} with legacy Pax Web servier registration properties</li>
 *     <li>{@link ServletContextHelper} with standard properties and/or annotations</li>
 *     <li>{@link org.ops4j.pax.web.service.whiteboard.HttpContextMapping}</li>
 *     <li>{@link org.ops4j.pax.web.service.whiteboard.ServletContextHelperMapping}</li>
 * </ul>
 * There's yet another internal relationship. Single {@link OsgiContextModel}, while related 1:1 with single
 * <em>ephemeral</em> context, it can be associated with multiple {@link ServletContextHelper} or
 * {@link HttpContext} instances, because the original {@link ServiceReference} can represent
 * {@link org.osgi.framework.ServiceFactory}, so many bundles may obtain different instances of target
 * context.</p>
 *
 * <p>Discovered service registration properties are stored as well to ensure proper context selection according
 * to 140.3 Common Whiteboard Properties.</p>
 *
 * <p>The most important role is to wrap actual {@link HttpContext} or
 * {@link ServletContextHelper} that'll be used when given servlet will be accessing
 * own {@link ServletContext}, to comply with Whiteboard Specification.</p>
 *
 * <p>While many {@link OsgiContextModel OSGi-related contexts} may point to single {@link ServletContextModel} and
 * contribute different web elements (like some bundles provide servlets and other bundle provide login configuration),
 * some aspects need conflict resolution - for example session timeout setting. Simply highest ranked
 * {@link OsgiContextModel} will be the one providing the configuration for given {@link ServletContextModel}.</p>
 *
 * <p>Some aspects of {@link ServletContext} visible to registered element are however dependent on which particular
 * {@link OsgiContextModel} was used. Resource access will be done through {@link HttpContext} or
 * {@link ServletContextHelper} and context parameters will be stored in this
 * class (remember: there can be different {@link OsgiContextModel} for the same {@link ServletContextModel}, but
 * providing different init parameters ({@code <context-param>} from {@code web.xml}).</p>
 *
 * <p>Another zen-like question: there may be two different {@link ServletContextHelper}
 * services registered for the same <em>context path</em> with different
 * {@link ServletContextHelper#handleSecurity}. Then two filters are registered
 * to both of such contexts - looks like when sending an HTTP request matching this common <em>context path</em>,
 * both {@code handleSecurity()} methods must be called before entering the filter pipeline. Fortunately
 * specification is clear about it. "140.5 Registering Servlet Filters" says:<blockquote>
 *     Servlet filters are only applied to servlet requests if they are bound to the same Servlet Context Helper
 *     and the same Http Whiteboard implementation.
 * </blockquote></p>
 *
 * <p>In Felix-HTTP, N:1 mapping between many {@link OsgiContextModel} and {@link ServletContextModel} relationship
 * is handled by {@code org.apache.felix.http.base.internal.registry.PerContextHandlerRegistry}. And
 * {@code org.apache.felix.http.base.internal.registry.HandlerRegistry#registrations} is sorted using 3 criteria:<ul>
 *     <li>context path length: longer path, higher priority</li>
 *     <li>service rank: higher rank, higher priority</li>
 *     <li>service id: higher id, lower priority</li>
 * </ul></p>
 *
 * <p><em>Shadowing</em> {@link OsgiContextModel} (see
 * {@link org.osgi.service.http.runtime.dto.DTOConstants#FAILURE_REASON_SHADOWED_BY_OTHER_SERVICE}) can happen
 * <strong>only</strong> when there's name/id conflict, so:<ul>
 *     <li>When there are two contexts with same name and different context path, one is chosen (using ranking)
 *     - that's the way to override {@code default} context, for example by changing its context path</li>
 *     <li>When there are two contexts with different name and same context path, both are used, because there may
 *     be two Whiteboard servlets registered, associated with both OSGi contexts</li>
 *     <li>If one servlet is associated with two {@link OsgiContextModel} pointing to the same context path, only
 *     one should be used - again, according to service ranking</li>
 * </ul></p>
 *
 * <p>{@link OsgiContextModel} may represent legacy (Http Service specification) <em>context</em> and standard
 * (Whiteboard Service specification) <em>context</em>. If it's created (<em>customized</em>) for {@link HttpContext}
 * (registered directly or via {@link org.ops4j.pax.web.service.whiteboard.HttpContextMapping}) and if it's a
 * singleton, then such {@link OsgiContextModel} is equivalent to one created directly through
 * {@link org.osgi.service.http.HttpService} and user may continue to register servlets via
 * {@link org.osgi.service.http.HttpService} to such contexts. That's the way to change the context path of such
 * context.</p>
 *
 * @author Alin Dreghiciu
 * @author Grzegorz Grzybek
 * @since 0.3.0, December 29, 2007
 */
public final class OsgiContextModel extends Identity implements Comparable<OsgiContextModel> {

	public static Logger LOG = LoggerFactory.getLogger(OsgiContextModel.class);

	/** The singleton {@link OsgiContextModel} used both by pax-web-runtime and pax-web-extender-whiteboard */
	public static final OsgiContextModel DEFAULT_CONTEXT_MODEL;

	static {
		// bundle that "registered" the default ServletContextHelper according to "140.2 The Servlet Context"
		// it's not relevant, because the actual ServletContextHelper will be bound to the bundle for which
		// actual servlet was registered.
		// TOCHECK: what about filter-only pipeline? From which bundle the resources will be loaded?
		Bundle bundle = FrameworkUtil.getBundle(OsgiContextModel.class);

		// tricky way to specify that Whiteboard's "context" is easily overrideable, but still much higher ranked
		// than OsgiContextModels registered for name+bundle pairs from HttpService instance(s)
		DEFAULT_CONTEXT_MODEL = new OsgiContextModel(bundle, Integer.MIN_VALUE / 2, 0L);
		DEFAULT_CONTEXT_MODEL.setName(HttpWhiteboardConstants.HTTP_WHITEBOARD_DEFAULT_CONTEXT_NAME);
		DEFAULT_CONTEXT_MODEL.setContextPath(PaxWebConstants.DEFAULT_CONTEXT_PATH);

		// the "instance" of the ServletContextHelper will be set as supplier, so it'll depend on the
		// bundle context for which the web element (like servlet) is registered
		// that's the default implementation of "140.2 The Servlet Context" chapter
		// instance of org.osgi.service.http.context.ServletContextHelper will be used. It's abstract, but without
		// any abstract methods
		DEFAULT_CONTEXT_MODEL.setContextSupplier((context, contextName) -> {
			Bundle whiteboardBundle = context == null ? null : context.getBundle();
			return new WebContainerContextWrapper(whiteboardBundle, new DefaultServletContextHelper(whiteboardBundle),
					contextName);
		});

		Hashtable<String, Object> registration = DEFAULT_CONTEXT_MODEL.getContextRegistrationProperties();
		registration.clear();
		// We pretend that this ServletContextModel was:
		//  - registered to represent the Whiteboard's "default" context (org.osgi.service.http.context.ServletContextHelper)
		registration.put(HttpWhiteboardConstants.HTTP_WHITEBOARD_CONTEXT_NAME, HttpWhiteboardConstants.HTTP_WHITEBOARD_DEFAULT_CONTEXT_NAME);
		//  - NOT registered to represent the HttpService's "default" context (org.osgi.service.http.HttpContext)
		registration.remove(HttpWhiteboardConstants.HTTP_SERVICE_CONTEXT_PROPERTY);
		registration.put(Constants.SERVICE_ID, DEFAULT_CONTEXT_MODEL.getServiceId());
		registration.put(Constants.SERVICE_RANKING, DEFAULT_CONTEXT_MODEL.getServiceRank());
		//  - registered with "/" context path
		registration.put(HttpWhiteboardConstants.HTTP_WHITEBOARD_CONTEXT_PATH, PaxWebConstants.DEFAULT_CONTEXT_PATH);

		// no context.init.* properties
		DEFAULT_CONTEXT_MODEL.getContextParams().clear();
	}

	/**
	 * 1:1 mapping to <em>server specific</em> {@link ServletContextModel}. Though we don't need entire model,
	 * especially when we don't have all the data required for {@link ServletContextModel}, so we keep only
	 * the context path - a <em>key</em> to {@link ServletContextModel}.
	 */
	private String contextPath;

	/** If this name is set, it'll be used in associated {@link WebContainerContext} */
	private String name = null;

	/**
	 * <p>Actual OSGi-specific <em>context</em> (can be {@link HttpContext} or
	 * {@link ServletContextHelper} wrapper) that'll be used by {@link ServletContext}
	 * object visible to web elements associated with this OSGi-specific context.</p>
	 *
	 * <p>If this context {@link WebContainerContext#isShared() allows sharing}, {@link OsgiContextModel} can be
	 * populated by different bundles, but still, the helper {@link HttpContext} or
	 * {@link ServletContextHelper} comes from single bundle that has <em>started</em>
	 * configuration/population of given {@link OsgiContextModel}.</p>
	 *
	 * <p>This context may not be set directly. If it's {@code null}, then {@link #resolveHttpContext(Bundle)}
	 * should <em>resolve</em> the {@link WebContainerContext} on each call - to bind returned context with proper
	 * bundle.</p>
	 */
	private WebContainerContext httpContext;

	/**
	 * <p>When a <em>context</em> is registered as Whiteboard service, we have to keep the reference here, because
	 * actual service may be a {@link org.osgi.framework.ServiceFactory}, so it has to be dereferenced within
	 * the context (...) of actual web element (like Servlet).</p>
	 *
	 * <p>The type of the reference is not known, because it can be:<ul>
	 *     <li>{@link ServletContextHelper} - as specified in Whiteboard Service (the recommended way)</li>
	 *     <li>{@link org.ops4j.pax.web.service.whiteboard.HttpContextMapping} - legacy Pax Web way for legacy
	 *         context</li>
	 *     <li>{@link org.ops4j.pax.web.service.whiteboard.ServletContextHelperMapping} - legacy Pax Web way for new
	 *         context</li>
	 *     <li>{@link HttpContext} - not specified anywhere, but supported...</li>
	 * </ul></p>
	 *
	 * <p>Such reference is <strong>not</strong> used if {@link #httpContext} is provided directly, but even if user
	 * registers {@link org.ops4j.pax.web.service.whiteboard.HttpContextMapping} which could provide
	 * {@link HttpContext} directly, we keep the reference to obtain the
	 * {@link org.ops4j.pax.web.service.whiteboard.HttpContextMapping} when needed.</p>
	 */
	private ServiceReference<?> contextReference;

	/**
	 * If user registers {@link org.ops4j.pax.web.service.whiteboard.HttpContextMapping} or
	 * {@link org.ops4j.pax.web.service.whiteboard.ServletContextHelperMapping}, those methods MAY return
	 * {@link org.ops4j.pax.web.service.WebContainerContext} as new instance on each call to relevant {@code get()}
	 * method. In Whiteboard Service specification, everything revolves around passing {@link ServiceReference} around,
	 * here we use {@link Function}, because the resulting {@link WebContainerContext} may (or rather should) be
	 * associated with some {@link BundleContext}.
	 */
	private BiFunction<BundleContext, String, WebContainerContext> contextSupplier;

	/**
	 * Properties used when {@link HttpContext} or {@link ServletContextHelper}
	 * was registered. Used for context selection by any LDAP-style filter.
	 */
	private final Hashtable<String, Object> contextRegistrationProperties = new Hashtable<>();

	/**
	 * <p>Context parameters as defined by {@link ServletContext#getInitParameterNames()} and
	 * represented by {@code <context-param>} elements if {@code web.xml}.</p>
	 *
	 * <p>Keeping the parameters at OSGi-specific <em>context</em> level instead of server-specific <em>context</em>
	 * level allows to access different parameters for servlets registered with different {@link HttpContext} or
	 * {@link ServletContextHelper} while still pointing to the same
	 * {@link ServletContext}.</p>
	 *
	 * <p>These parameters come from {@code context.init.*} service registration properties.</p>
	 */
	private final Map<String, String> contextParams = new HashMap<>();

	/**
	 * <p>Virtual Host List as specified when {@link ServletContextHelper},
	 * {@link HttpContext} or {@link org.ops4j.pax.web.service.whiteboard.ContextMapping} was registered.</p>
	 *
	 * <p>For each VHost from the list, related {@link ServletContextModel} should be added to given VHost.
	 * Empty list means the {@link ServletContextModel} is part of all, including default (fallback), VHosts.</p>
	 */
	private final List<String> virtualHosts = new ArrayList<>();

	/**
	 * This is the <em>owner</em> bundle of this <em>context</em>. For {@link org.osgi.service.http.HttpService}
	 * scenario, that's the bundle of bundle-scoped {@link org.osgi.service.http.HttpService} used to create
	 * {@link HttpContext}. For Whiteboard scenario, that's the bundle registering
	 * {@link ServletContextHelper}. For old Pax Web Whiteboard, that can be a
	 * bundle which registered <em>shared</em> {@link HttpContext}.
	 */
	private Bundle ownerBundle;

	/** Registration rank of associated {@link HttpContext} or {@link ServletContextHelper} */
	private int serviceRank = 0;
	/** Registration service.id of associated {@link HttpContext} or {@link ServletContextHelper} */
	private long serviceId = 0L;

	private Boolean isValid;

	public OsgiContextModel(Bundle ownerBundle, Integer rank, Long serviceId) {
		this.ownerBundle = ownerBundle;
		this.serviceRank = rank;
		this.serviceId = serviceId;

		contextRegistrationProperties.put(Constants.SERVICE_ID, serviceId);
		contextRegistrationProperties.put(Constants.SERVICE_RANKING, rank);
	}

	public OsgiContextModel(WebContainerContext httpContext, Bundle ownerBundle) {
		this.httpContext = httpContext;
		this.ownerBundle = ownerBundle;
	}

	public OsgiContextModel(WebContainerContext httpContext, Bundle ownerBundle, String contextPath) {
		this.httpContext = httpContext;
		this.ownerBundle = ownerBundle;
		this.contextPath = contextPath;
	}

	/**
	 * <p>This method should be called from Whiteboard infrastructure to really perform the validation and set
	 * <em>isValid</em> flag, which is then used for "Failure DTO" purposes.</li>
	 * TODO: different exceptions or calbacks for DTO purposes
	 */
	public boolean isValid() {
		if (isValid == null) {
			try {
				isValid = performValidation();
			} catch (Exception ignored) {
				isValid = false;
			}
		}
		return isValid;
	}

	/**
	 * <p>Perform context-specific validation and throws different exceptions when needed.</p>
	 *
	 * <p>This method should be called in Http Service scenario where we immediately need strong feedback - with
	 * exceptions thrown for all validation problems.</p>
	 *
	 * @return
	 */
	public Boolean performValidation() throws Exception {
		if (name == null || "".equals(name.trim())) {
			if (contextReference != null) {
				LOG.warn("Missing name property for context: {}", contextReference);
			}
			return Boolean.FALSE;
		}

		return Boolean.TRUE;
	}

	/**
	 * <p>This method gets a {@link WebContainerContext} asociated with given model. If the context is not
	 * configured directly, we may need to dereference it if needed.</p>
	 *
	 * <p>It's both obvious and conformant to Whiteboard Service specification, that {@link ServletContextHelper}
	 * may be registered as {@link org.osgi.framework.ServiceFactory} (and <em>default</em> one <em>behaves</em>
	 * exactly like this), so it must be dereferenced within a context of given Whiteboard service's (e.g., Servlet's)
	 * {@link BundleContext}).</p>
	 *
	 * <p>Similar case is in Pax Web, when user registers {@link org.ops4j.pax.web.service.whiteboard.HttpContextMapping}
	 * or {@link org.ops4j.pax.web.service.whiteboard.ServletContextHelperMapping}. Relevant method may create new
	 * instance of the context on each call (but the {@code getHttpContext()}/{@code getServletContextHelper()} doesn't
	 * accept {@link BundleContext}).</p>
	 *
	 * @param bundleContext
	 * @return
	 */
	public WebContainerContext resolveHttpContext(Bundle bundle) {
		if (httpContext != null) {
			return httpContext;
		}

		BundleContext bundleContext = bundle != null ? bundle.getBundleContext() : null;
		if (bundleContext == null) {
			throw new IllegalArgumentException("Can't resolve WebContainerContext without Bundle argument");
		}

		if (contextSupplier != null) {
			// HttpContextMapping and ServletContextHelperMapping cases are handled via contextSupplier
			return contextSupplier.apply(bundleContext, getName());
		}
		if (contextReference != null) {
			// TODO: the hardest part. All returned services SHOULD be unget when no longer used
			LOG.debug("Dereferencing {} for {}", contextReference, bundleContext);

			Object context = bundleContext.getService(contextReference);
			if (context instanceof WebContainerContext) {
				return (WebContainerContext) context;
			}
			if (context instanceof HttpContext) {
				// the very legacy way, because HttpContext was never designed to be registered as Whiteboard service
				return new WebContainerContextWrapper(bundleContext.getBundle(), (HttpContext) context, name);
			}
			if (context instanceof ServletContextHelper) {
				// the preferred way
				return new WebContainerContextWrapper(bundleContext.getBundle(), (ServletContextHelper) context, name);
			}

			throw new IllegalStateException("Unsupported Whiteboard service for HttpContext/ServletContextHelper"
					+ " specified");
		}

		throw new IllegalStateException("No HttpContext/ServletContextHelper configured for " + this);
	}

	/**
	 * If {@link WebContainerContext} was obtained via {@link ServiceReference}, it <strong>has to</strong> be
	 * unget later.
	 *
	 * @param context
	 */
	public void releaseHttpContext(WebContainerContext context) {

	}

	public String getName() {
		return name;
	}

	public void setName(String name) {
		this.name = name;
	}

	public Map<String, String> getContextParams() {
		return contextParams;
	}

	public Hashtable<String, Object> getContextRegistrationProperties() {
		return contextRegistrationProperties;
	}

	public List<String> getVirtualHosts() {
		return virtualHosts;
	}

	public Bundle getOwnerBundle() {
		return ownerBundle;
	}

	public void setOwnerBundle(Bundle ownerBundle) {
		this.ownerBundle = ownerBundle;
	}

	public String getContextPath() {
		return contextPath;
	}

	public void setContextPath(String contextPath) {
		this.contextPath = contextPath;
	}

	public int getServiceRank() {
		return serviceRank;
	}

	public void setServiceRank(int serviceRank) {
		this.serviceRank = serviceRank;
	}

	public long getServiceId() {
		return serviceId;
	}

	public void setServiceId(long serviceId) {
		this.serviceId = serviceId;
	}

	public ServiceReference<?> getContextReference() {
		return contextReference;
	}

	public void setContextReference(ServiceReference<?> contextReference) {
		this.contextReference = contextReference;
	}

	public BiFunction<BundleContext, String, WebContainerContext> getContextSupplier() {
		return contextSupplier;
	}

	public void setContextSupplier(BiFunction<BundleContext, String, WebContainerContext> contextSupplier) {
		this.contextSupplier = contextSupplier;
	}

	public void setHttpContext(WebContainerContext httpContext) {
		this.httpContext = httpContext;
	}

	/**
	 * Checks if this {@link OsgiContextModel} has direct instance (not bound to any {@link Bundle}) of
	 * {@link WebContainerContext}. Such {@link OsgiContextModel} represents the <em>context</em> from the point
	 * of view of Http Service specification (in Whiteboard, <em>context</em> should be obtained from service registry
	 * when needed, because it's recommended to register it as {@link org.osgi.framework.ServiceFactory}).
	 * @return
	 */
	public boolean hasDirectHttpContextInstance() {
		return httpContext != null;
	}

	@Override
	public String toString() {
		return "OsgiContextModel{id=" + getId()
				+ ",name='" + name
				+ "',contextPath='" + contextPath
				+ "',context=" + httpContext
				+ (ownerBundle == null ? ",shared=true" : ",bundle=" + ownerBundle)
				+ "}";
	}

	@Override
	public int compareTo(OsgiContextModel o) {
		String cp1 = this.getContextPath();
		String cp2 = o.getContextPath();

		// reverse check - longer path is "first"
		int pathLength = cp2.length() - cp1.length();
		if (pathLength != 0) {
			return pathLength;
		}

		int pathItself = cp1.compareTo(cp2);
		if (pathItself != 0) {
			// no conflict - different contexts
			return pathItself;
		}

		// reverse check for ranking - higher rank is "first"
		int serviceRank = o.getServiceRank() - this.getServiceRank();
		if (serviceRank != 0) {
			return serviceRank;
		}

		// service ID - lower is "first"
		long serviceId = this.getServiceId() - o.getServiceId();
		if (serviceId != 0L) {
			return (int) serviceId;
		}

		// fallback case - mostly in tests cases
		return this.getNumericId() - o.getNumericId();
	}



















//	/** Access controller context of the bundle that registered the http context. */
//	@Review("it's so rarely used - only in one resource access scenario, though there are many such scenarios.")
//	private final AccessControlContext accessControllerContext;
//
//	/**
//	 * Registered jsp servlets for this context.
//	 */
//	private Map<Servlet, String[]> jspServlets;
//
//	private final Boolean showStacks;
//
//	/**
//	 * Jetty Web XML URL
//	 */
//	private URL jettyWebXmlUrl;

//	@SuppressWarnings("rawtypes")
//	public void setContextParams(final Dictionary contextParameters) {
//		contextParams.clear();
//		if (contextParameters != null && !contextParameters.isEmpty()) {
//			final Enumeration keys = contextParameters.keys();
//			while (keys.hasMoreElements()) {
//				final Object key = keys.nextElement();
//				final Object value = contextParameters.get(key);
//				if (!(key instanceof String) || !(value instanceof String)) {
//					throw new IllegalArgumentException(
//							"Context params keys and values must be Strings");
//				}
//				contextParams.put((String) key, (String) value);
//			}
//			contextName = contextParams.get(PaxWebConstants.CONTEXT_NAME);
//		}
//		if (contextName != null) {
//			contextName = contextName.trim();
//		} else {
//			contextName = "";
//		}
//	}
//	/**
//	 * Getter.
//	 *
//	 * @return jsp servlet
//	 */
//	public Map<Servlet, String[]> getJspServlets() {
//		return jspServlets;
//	}
//	/**
//	 * Getter.
//	 *
//	 * @return the access controller context of the bundle that registred the
//	 * context
//	 */
//	public AccessControlContext getAccessControllerContext() {
//		return accessControllerContext;
//	}
//
//	public Boolean isShowStacks() {
//		return showStacks;
//	}
//
//	public void setJettyWebXmlUrl(URL jettyWebXmlUrl) {
//		this.jettyWebXmlUrl = jettyWebXmlUrl;
//	}
//
//	public URL getJettyWebXmlURL() {
//		return jettyWebXmlUrl;
//	}

}