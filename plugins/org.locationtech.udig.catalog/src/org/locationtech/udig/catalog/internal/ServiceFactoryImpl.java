/**
 * uDig - User Friendly Desktop Internet GIS client
 * http://udig.refractions.net
 * (C) 2004-2012, Refractions Research Inc.
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * (http://www.eclipse.org/legal/epl-v10.html), and the Refractions BSD
 * License v1.0 (http://udig.refractions.net/files/bsd3-v10.html).
 *
 */
package org.locationtech.udig.catalog.internal;

import java.io.Serializable;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.SubProgressMonitor;
import org.locationtech.udig.catalog.CatalogPlugin;
import org.locationtech.udig.catalog.IService;
import org.locationtech.udig.catalog.IServiceFactory;
import org.locationtech.udig.catalog.ServiceExtension;
import org.locationtech.udig.catalog.interceptor.ServiceInterceptor;

/**
 * Default implementation of IServiceFactory used by the local catalog.
 * <p>
 * This service factory is careful not to test the connections (ie resolve to IServiceInfo) during
 * creation. It is making the assumption that the parameters were already checked (and are thus
 * valid), the resulting IService handles are thus untested and not connected as behaves the
 * application when quickly starting up.
 *
 * @author David Zwiers, Refractions Research
 * @since 0.6
 * @version 1.3
 */
public class ServiceFactoryImpl extends IServiceFactory {

    /**
     * Check if the service extension is "generic" placeholder where a more specific implementation
     * may exist.
     * <p>
     * An extension point flag should be used; for now we will just check the identifier itself.
     *
     * @param serviceExtension
     * @return true if this service extension is generic
     */
    private boolean isGeneric(ServiceExtension serviceExtension) {
        String name = serviceExtension.getClass().getName();
        return name.toLowerCase().contains("geotools"); //$NON-NLS-1$
    }

    /**
     * List candidate IService handles generated by all ServiceExtentions that think they can handle
     * the provided target drag and drop URL.
     * <p>
     * Note: Just because a target is created does *NOT* mean it will actually work. You can check
     * the handles in the usual manner (ask for their info) after you get back this list.
     * </p>
     *
     * @see org.locationtech.udig.catalog.IServiceFactory#acquire(java.net.URL)
     * @param target Target URL usually provided by drag and drop code
     * @return List of candidate services
     */
    @Override
    public List<IService> createService(final URL targetUrl) {
        final Map<ServiceExtension, Map<String, Serializable>> available = new HashMap<>();
        // lazy load parameters of generic Services only if no other ServiceExtension is available
        final List<ServiceExtension> generic = new ArrayList<>();

        for (ServiceExtension serviceExtension : CatalogPlugin.getDefault()
                .getServiceExtensions()) {
            if (isGeneric(serviceExtension)) {
                generic.add(serviceExtension);
                continue;
            }

            try {
                Map<String, Serializable> defaultParams = serviceExtension.createParams(targetUrl);
                if (defaultParams != null) {
                    available.put(serviceExtension, defaultParams);
                }
            } catch (Throwable t) {
                if (CatalogPlugin.getDefault().isDebugging()) {
                    String name = serviceExtension.getClass().getName();
                    IStatus warning = new Status(IStatus.WARNING, CatalogPlugin.ID,
                            name + " could not create params " + targetUrl, t); //$NON-NLS-1$
                    CatalogPlugin.getDefault().getLog().log(warning);
                }
            }
        }
        List<IService> candidates = new LinkedList<>();
        if (!available.isEmpty()) {
            for (Map.Entry<ServiceExtension, Map<String, Serializable>> candidateEntry : available
                    .entrySet()) {
                String extentionIdentifier = candidateEntry.getKey().getClass().getName();
                ServiceExtension serviceExtension = candidateEntry.getKey();
                Map<String, Serializable> connectionParameters = candidateEntry.getValue();
                try {
                    IService sevice = serviceExtension.createService(null, connectionParameters);
                    if (sevice == null) {
                        continue;
                    }
                    CatalogImpl.runInterceptor(sevice, ServiceInterceptor.CREATED_ID);
                    candidates.add(sevice);
                } catch (Throwable deadService) {
                    CatalogPlugin.log(extentionIdentifier + " could not create service", //$NON-NLS-1$
                            deadService);
                }
            }
        }
        if (candidates.isEmpty() && !generic.isEmpty()) {
            // add generic entries if needed
            for (ServiceExtension serviceExtension : generic) {
                try {
                    Map<String, Serializable> connectionParameters = serviceExtension
                            .createParams(targetUrl);
                    if (connectionParameters != null) {
                        IService service = serviceExtension.createService(null,
                                connectionParameters);
                        CatalogImpl.runInterceptor(service, ServiceInterceptor.CREATED_ID);
                        candidates.add(service);
                    }
                } catch (Throwable deadService) {
                    CatalogPlugin.log(
                            serviceExtension.getClass().getName() + " could not create service", //$NON-NLS-1$
                            deadService);
                }
            }
        }
        return candidates;
    }

    @Override
    public List<IService> createService(final Map<String, Serializable> connectionParameters) {
        final List<IService> services = new LinkedList<>();

        for (ServiceExtension serviceExtension : CatalogPlugin.getDefault()
                .getServiceExtensions()) {
            String name = serviceExtension.getClass().getName();
            if (isGeneric(serviceExtension)) {
                continue; // skip generic for this pass
            }
            try {
                // Attempt to construct a service, and add to the list if available.

                // Put a break point here to watch every serviceExtension try and connect
                IService service = serviceExtension.createService(null, connectionParameters);
                if (service != null) {
                    CatalogImpl.runInterceptor(service, ServiceInterceptor.CREATED_ID);
                    services.add(service);
                }
            } catch (Throwable deadService) {
                CatalogPlugin.log(name + " could not create service", deadService); //$NON-NLS-1$
            }
        }
        if (services.isEmpty()) {
            for (ServiceExtension serviceExtension : CatalogPlugin.getDefault()
                    .getServiceExtensions()) {
                String name = serviceExtension.getClass().getName();
                if (!isGeneric(serviceExtension)) {
                    continue; // only generic for this pass
                }
                try {
                    // Attempt to construct a service, and add to the list if available.
                    IService service = serviceExtension.createService(null, connectionParameters);
                    if (service != null) {
                        CatalogImpl.runInterceptor(service, ServiceInterceptor.CREATED_ID);
                        services.add(service);
                    }
                } catch (Throwable deadService) {
                    deadService.printStackTrace();
                    CatalogPlugin.trace(name + " could not create service", deadService); //$NON-NLS-1$
                }
            }
        }
        return services;
    }

    @Override
    public void dispose(List<IService> list, IProgressMonitor monitor) {
        if (list == null)
            return;

        if (monitor == null)
            monitor = new NullProgressMonitor();
        monitor.beginTask("dispose", list.size() * 10); //$NON-NLS-1$
        for (IService service : list) {
            try {
                service.dispose(new SubProgressMonitor(monitor, 10));
            } catch (Throwable t) {
                CatalogPlugin.trace("Dispose " + service, t); //$NON-NLS-1$
            }
        }
        monitor.done();
    }
}
