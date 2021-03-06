/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.brooklyn.entity.dns;

import static com.google.common.base.Preconditions.checkNotNull;

import java.net.InetAddress;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.net.UnknownHostException;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import org.apache.brooklyn.api.entity.Entity;
import org.apache.brooklyn.api.entity.Group;
import org.apache.brooklyn.api.policy.PolicySpec;
import org.apache.brooklyn.api.sensor.Sensor;
import org.apache.brooklyn.core.entity.AbstractEntity;
import org.apache.brooklyn.core.entity.Attributes;
import org.apache.brooklyn.core.entity.lifecycle.Lifecycle;
import org.apache.brooklyn.core.entity.lifecycle.ServiceStateLogic;
import org.apache.brooklyn.core.entity.lifecycle.ServiceStateLogic.ServiceNotUpLogic;
import org.apache.brooklyn.core.location.geo.HostGeoInfo;
import org.apache.brooklyn.entity.group.AbstractMembershipTrackingPolicy;
import org.apache.brooklyn.entity.group.DynamicGroup;
import org.apache.brooklyn.entity.webapp.WebAppService;
import org.apache.brooklyn.util.collections.MutableSet;
import org.apache.brooklyn.util.core.flags.SetFromFlag;
import org.apache.brooklyn.util.exceptions.Exceptions;
import org.apache.brooklyn.util.net.Networking;
import org.apache.brooklyn.util.time.Duration;
import org.apache.brooklyn.util.time.Time;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Maps;

public abstract class AbstractGeoDnsServiceImpl extends AbstractEntity implements AbstractGeoDnsService {
    private static final Logger log = LoggerFactory.getLogger(AbstractGeoDnsService.class);

    @SetFromFlag
    protected Group targetEntityProvider;
    protected AbstractMembershipTrackingPolicy tracker;
    /** Guards access to {@link #tracker}. */
    protected final Object trackerLock = new Object[0];

    protected Map<Entity, HostGeoInfo> targetHosts = Collections.synchronizedMap(new LinkedHashMap<Entity, HostGeoInfo>());

    // We complain (at debug) when we encounter a target entity for whom we can't derive hostname/ip information; 
    // this is the commonest case for the transient condition between the time the entity is created and the time 
    // it is started (at which point the location is specified). This set contains those entities we've complained 
    // about already, to avoid repetitive logging.
    transient protected Set<Entity> entitiesWithoutHostname = new HashSet<Entity>();

    // We complain (at info/warn) when we encounter a target entity for whom we can't derive geo information, even 
    // when hostname/ip is known. This set contains those entities we've complained about already, to avoid repetitive 
    // logging.
    transient protected Set<Entity> entitiesWithoutGeoInfo = new HashSet<Entity>();

    public AbstractGeoDnsServiceImpl() {
        super();
    }

    @Override
    public void init() {
        super.init();
        Group initialProvider = config().get(ENTITY_PROVIDER);
        if (initialProvider != null) {
            setTargetEntityProvider(initialProvider);
        }
    }

    @Override
    public Map<Entity, HostGeoInfo> getTargetHosts() {
        return targetHosts;
    }

    @Override
    public void onManagementStarted() {
        super.onManagementStarted();
        startTracker();
    }

    @Override
    public void onManagementStopped() {
        endTracker();
        super.onManagementStopped();
    }

    @Override
    public void destroy() {
        setServiceState(Lifecycle.DESTROYED);
        super.destroy();
    }

    @Override
    public void setServiceState(Lifecycle state) {
        sensors().set(HOSTNAME, getHostname());
        ServiceStateLogic.setExpectedState(this, state);
        if (state==Lifecycle.RUNNING)
            ServiceNotUpLogic.clearNotUpIndicator(this, SERVICE_STATE_ACTUAL);
        else
            ServiceNotUpLogic.updateNotUpIndicator(this, SERVICE_STATE_ACTUAL, "Not in RUNNING state");
    }

    @Override
    public void setTargetEntityProvider(final Group entityProvider) {
        this.targetEntityProvider = checkNotNull(entityProvider, "targetEntityProvider");
        startTracker();
    }

    /** should set up so these hosts are targeted, and setServiceState appropriately */
    protected abstract void reconfigureService(Collection<HostGeoInfo> targetHosts);

    /**
     * Start the tracker.
     * <p>
     * Subclasses should take care to synchronize on {@link #trackerLock}.
     */
    protected void startTracker() {
        synchronized (trackerLock) {
            if (targetEntityProvider == null || !getManagementSupport().isDeployed()) {
                log.debug("Tracker for " + this + " not yet active: " + targetEntityProvider + " / " + getManagementContext());
                return;
            }
            endTracker();

            ImmutableSet.Builder<Sensor<?>> sensorsToTrack = ImmutableSet.<Sensor<?>>builder().add(
                    HOSTNAME, ADDRESS, Attributes.MAIN_URI, WebAppService.ROOT_URL);
            // Don't subscribe to lifecycle events if entities will be included regardless of their status.
            if (Boolean.TRUE.equals(config().get(FILTER_FOR_RUNNING))) {
                sensorsToTrack.add(Attributes.SERVICE_STATE_ACTUAL);
            }
            log.debug("Initializing tracker for " + this + ", following " + targetEntityProvider);
            tracker = policies().add(PolicySpec.create(MemberTrackingPolicy.class)
                    .displayName("GeoDNS targets tracker")
                    .configure(AbstractMembershipTrackingPolicy.SENSORS_TO_TRACK, sensorsToTrack.build())
                    .configure(AbstractMembershipTrackingPolicy.GROUP, targetEntityProvider));
            refreshGroupMembership();
        }
    }

    /**
     * End the tracker.
     * <p>
     * Subclasses should take care to synchronize on {@link #trackerLock}.
     */
    protected void endTracker() {
        synchronized (trackerLock) {
            if (tracker == null || targetEntityProvider == null) return;
            policies().remove(tracker);
            tracker = null;
        }
    }

    public static class MemberTrackingPolicy extends AbstractMembershipTrackingPolicy {
        @Override
        protected void onEntityEvent(EventType type, Entity entity) {
            ((AbstractGeoDnsServiceImpl)super.entity).refreshGroupMembership();
        }
    }

    @Override
    public abstract String getHostname();

    long lastUpdate = -1;

    // TODO: remove group member polling once locations can be determined via subscriptions
    protected void refreshGroupMembership() {
        try {
            if (log.isDebugEnabled()) log.debug("GeoDns {} refreshing targets", this);
            if (targetEntityProvider == null)
                return;
            if (targetEntityProvider instanceof DynamicGroup)
                ((DynamicGroup) targetEntityProvider).rescanEntities();
            Set<Entity> pool = MutableSet.copyOf(targetEntityProvider instanceof Group ? targetEntityProvider.getMembers(): targetEntityProvider.getChildren());
            if (log.isDebugEnabled()) log.debug("GeoDns {} refreshing targets, pool now {}", this, pool);

            boolean changed = false;
            boolean filterForRunning = Boolean.TRUE.equals(config().get(FILTER_FOR_RUNNING));
            Set<Entity> previousOnes = MutableSet.copyOf(targetHosts.keySet());
            for (Entity e: pool) {
                if (!filterForRunning || Lifecycle.RUNNING.equals(e.sensors().get(Attributes.SERVICE_STATE_ACTUAL))) {
                    previousOnes.remove(e);
                    changed |= addTargetHost(e);
                }
            }
            // anything left in previousOnes is no longer applicable
            for (Entity e: previousOnes) {
                changed |= removeTargetHost(e, false);
            }

            // do a periodic full update hourly once we are active (the latter is probably not needed)
            if (changed || (lastUpdate > 0 && Time.hasElapsedSince(lastUpdate, Duration.ONE_HOUR))) {
                update();
            }
        } catch (Exception e) {
            log.error("Problem refreshing group membership: "+e, e);
        }
    }

    /**
     * Adds this host, if it is absent or if its hostname has changed.
     * <p>
     * For whether to use hostname or ip, see config and attributes {@link AbstractGeoDnsService#USE_HOSTNAMES}, 
     * {@link Attributes#HOSTNAME} and {@link Attributes#ADDRESS} (via {@link #inferHostname(Entity)} and {@link #inferIp(Entity)}.
     * Note that the "hostname" could in fact be an IP address, if {@link #inferHostname(Entity)} returns an IP!
     * <p>
     * TODO in a future release, we may change this to explicitly set the sensor(s) to look at on the entity, and 
     * be stricter about using them in order.
     * 
     * @return true if host is added or changed
     */
    protected boolean addTargetHost(Entity entity) {
        try {
            HostGeoInfo oldGeo = targetHosts.get(entity);
            String hostname = inferHostname(entity);
            String ip = inferIp(entity);
            String addr = (getConfig(USE_HOSTNAMES) || ip == null) ? hostname : ip;

            if (addr==null) addr = ip;
            if (addr == null) {
                if (entitiesWithoutHostname.add(entity)) {
                    log.debug("GeoDns ignoring {} (no hostname/ip/URL info yet available)", entity);
                }
                return false;
            }

            // prefer the geo from the entity (or location parent), but fall back to inferring
            // e.g. if it supplies a URL
            HostGeoInfo geo = HostGeoInfo.fromEntity(entity);
            if (geo==null) geo = inferHostGeoInfo(hostname, ip);

            if (Networking.isPrivateSubnet(addr) && ip!=null && !Networking.isPrivateSubnet(ip)) {
                // fix for #1216
                log.debug("GeoDns using IP "+ip+" for "+entity+" as addr "+addr+" resolves to private subnet");
                addr = ip;
            }
            if (Networking.isPrivateSubnet(addr)) {
                if (getConfig(INCLUDE_HOMELESS_ENTITIES)) {
                    if (entitiesWithoutGeoInfo.add(entity)) {
                        log.info("GeoDns including {}, even though {} is a private subnet (homeless entities included)", entity, addr);
                    }
                } else {
                    if (entitiesWithoutGeoInfo.add(entity)) {
                        log.warn("GeoDns ignoring {} (private subnet detected for {})", entity, addr);
                    }
                    return false;
                }
            }

            if (geo == null) {
                if (getConfig(INCLUDE_HOMELESS_ENTITIES)) {
                    if (entitiesWithoutGeoInfo.add(entity)) {
                        log.info("GeoDns including {}, even though no geography info available for {})", entity, addr);
                    }
                    geo = HostGeoInfo.create(addr, "unknownLocation("+addr+")", 0, 0);
                } else {
                    if (entitiesWithoutGeoInfo.add(entity)) {
                        log.warn("GeoDns ignoring {} (no geography info available for {})", entity, addr);
                    }
                    return false;
                }
            }

            if (!addr.equals(geo.getAddress())) {
                // if the location provider did not have an address, create a new one with it
                geo = HostGeoInfo.create(addr, geo.displayName, geo.latitude, geo.longitude);
            }

            // If we already knew about it, and it hasn't changed, then nothing to do
            if (oldGeo != null && geo.getAddress().equals(oldGeo.getAddress())) {
                return false;
            }

            entitiesWithoutHostname.remove(entity);
            entitiesWithoutGeoInfo.remove(entity);
            log.info("GeoDns adding "+entity+" at "+geo+(oldGeo != null ? " (previously "+oldGeo+")" : ""));
            targetHosts.put(entity, geo);
            return true;

        } catch (Exception ee) {
            log.warn("GeoDns ignoring "+entity+" (error analysing location): "+ee, ee);
            return false;
        }
    }

    /** remove if host removed */
    protected boolean removeTargetHost(Entity e, boolean doUpdate) {
        if (targetHosts.remove(e) != null) {
            log.info("GeoDns removing reference to {}", e);
            if (doUpdate) update();
            return true;
        }
        return false;
    }

    protected void update() {
        lastUpdate = System.currentTimeMillis();

        Map<Entity, HostGeoInfo> m;
        synchronized(targetHosts) { m = ImmutableMap.copyOf(targetHosts); }
        if (log.isDebugEnabled()) log.debug("Full update of "+this+" ("+m.size()+" target hosts)");

        Map<String,String> entityIdToAddress = Maps.newLinkedHashMap();
        for (Map.Entry<Entity, HostGeoInfo> entry : m.entrySet()) {
            entityIdToAddress.put(entry.getKey().getId(), entry.getValue().address);
        }

        reconfigureService(new LinkedHashSet<HostGeoInfo>(m.values()));

        if (log.isDebugEnabled()) log.debug("Targets being set as "+entityIdToAddress);
        sensors().set(TARGETS, entityIdToAddress);
    }

    protected String inferHostname(Entity entity) {
        String hostname = entity.getAttribute(Attributes.HOSTNAME);
        URI url = entity.getAttribute(Attributes.MAIN_URI);
        if (url!=null) {
            try {
                URL u = url.toURL();

                String hostname2 = u.getHost(); 
                if (hostname==null) {
                    if (!entitiesWithoutGeoInfo.contains(entity))  //don't log repeatedly
                        log.warn("GeoDns "+this+" using URL {} to redirect to {} (HOSTNAME attribute is preferred, but not available)", url, entity);
                    hostname = hostname2;
                } else if (!hostname.equals(hostname2)) {
                    if (!entitiesWithoutGeoInfo.contains(entity))  //don't log repeatedly
                        log.warn("GeoDns "+this+" URL {} of "+entity+" does not match advertised HOSTNAME {}; using hostname, not URL", url, hostname);
                }

                if (u.getPort() > 0 && u.getPort() != 80 && u.getPort() != 443) {
                    if (!entitiesWithoutGeoInfo.contains(entity))  //don't log repeatedly
                        log.warn("GeoDns "+this+" detected non-standard port in URL {} for {}; forwarding may not work", url, entity);
                }

            } catch (MalformedURLException e) {
                log.warn("Invalid URL {} for entity {} in {}", new Object[] {url, entity, this});
            }
        }
        return hostname;
    }

    protected String inferIp(Entity entity) {
        return entity.getAttribute(Attributes.ADDRESS);
    }

    protected HostGeoInfo inferHostGeoInfo(String hostname, String ip) throws UnknownHostException {
        HostGeoInfo geoH = null;
        if (hostname != null) {
            try {
                // For some entities, the hostname can actually be an IP! Therefore use Networking.getInetAddressWithFixedName
                InetAddress addr = Networking.getInetAddressWithFixedName(hostname);
                geoH = HostGeoInfo.fromIpAddress(addr);
            } catch (RuntimeException e) {
                // Most likely caused by (a wrapped) UnknownHostException
                Exceptions.propagateIfFatal(e);
                if (ip == null) {
                    if (log.isTraceEnabled()) log.trace("inferHostGeoInfo failing ("+Exceptions.getFirstInteresting(e)+"): hostname="+hostname+"; ip="+ip);
                    throw e;
                } else {
                    if (log.isTraceEnabled()) log.trace("GeoDns failed to infer GeoInfo from hostname {}; will try with IP {} ({})", new Object[] {hostname, ip, e});
                }
            }
        }

        // Try IP address (prior to Mar 2014 we did not do this if USE_HOSTNAME was set but don't think that is desirable due to #1216)
        if (ip != null) {
            if (geoH == null) {
                InetAddress addr = Networking.getInetAddressWithFixedName(ip);
                geoH = HostGeoInfo.fromIpAddress(addr);
                if (log.isTraceEnabled()) log.trace("GeoDns inferred GeoInfo {} from ip {} (could not infer from hostname {})", new Object[] {geoH, ip, hostname});
            } else {
                geoH = HostGeoInfo.create(ip, geoH.displayName, geoH.latitude, geoH.longitude);
                if (log.isTraceEnabled()) log.trace("GeoDns inferred GeoInfo {} from hostname {}; switching it to ip {}", new Object[] {geoH, hostname, ip});
            }
        } else {
            if (log.isTraceEnabled()) log.trace("GeoDns inferred GeoInfo {} from hostname {}", geoH, hostname);
        }

        return geoH;
    }
}
