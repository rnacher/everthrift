package org.everthrift.appserver.model;

import net.sf.ehcache.Cache;
import net.sf.ehcache.CacheException;
import net.sf.ehcache.CacheManager;
import net.sf.ehcache.Ehcache;
import net.sf.ehcache.Element;
import net.sf.ehcache.Status;
import net.sf.ehcache.loader.CacheLoader;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.util.CollectionUtils;

import javax.annotation.PostConstruct;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;

public abstract class AbstractCachedModelFactory<PK, ENTITY> extends RoModelFactoryImpl<PK, ENTITY> {

    @Autowired(required = false)
    private CacheManager cm;

    private Cache cache;

    protected final String cacheName;

    private class CacheLoaderDecorator implements CacheLoader {

        private final CacheLoader orig;

        public CacheLoaderDecorator(CacheLoader orig) {
            super();
            this.orig = orig;
        }

        @Override
        public CacheLoader clone(Ehcache arg0) throws CloneNotSupportedException {
            throw new CloneNotSupportedException();
        }

        @Override
        public void dispose() throws CacheException {
        }

        @Override
        public String getName() {
            return cacheName + "Loader";
        }

        @Override
        public Status getStatus() {
            return Status.STATUS_ALIVE;
        }

        @Override
        public void init() {
        }

        @Override
        public Object load(Object arg0) throws CacheException {
            if (orig == null) {
                throw new RuntimeException("must use load(Object arg0, Object arg1)");
            } else {
                return orig.load(arg0);
            }
        }

        @Override
        public Object load(Object arg0, Object arg1) {
            return ((CacheLoader) arg1).load(arg0);
        }

        @Override
        public Map loadAll(Collection arg0) {
            if (orig == null) {
                throw new RuntimeException("must use loadAll(Object arg0, Object arg1)");
            } else {
                return orig.loadAll(arg0);
            }
        }

        @Override
        public Map loadAll(Collection arg0, Object arg1) {
            return ((CacheLoader) arg1).loadAll(arg0);
        }
    }

    private CacheLoader _loader = new CacheLoader() {
        @Override
        public CacheLoader clone(Ehcache arg0) throws CloneNotSupportedException {
            throw new CloneNotSupportedException();
        }

        @Override
        public void dispose() throws CacheException {

        }

        @Override
        public String getName() {
            return this.getClass().getCanonicalName() + ".Loader";
        }

        @Override
        public Status getStatus() {
            return Status.STATUS_ALIVE;
        }

        @Override
        public void init() {

        }

        @Override
        public Object load(Object arg0, Object arg1) {
            return load(arg0);
        }

        @Override
        public Object load(Object arg0) throws CacheException {
            return fetchEntityById((PK) arg0);
        }

        @Override
        public Map loadAll(Collection arg0, Object arg1) {
            return loadAll(arg0);
        }

        @Override
        public Map loadAll(Collection keys) {

            if (CollectionUtils.isEmpty(keys)) {
                return Collections.emptyMap();
            }

            if (keys.size() == 1) {
                final Object key = keys.iterator().next();
                return Collections.singletonMap(key, load(key));
            }

            return fetchEntityByIdAsMap(keys);
        }
    };

    /**
     * Конструктор создания фабрики не как Spring-bean
     */
    public AbstractCachedModelFactory(Cache cache) {
        super();

        if (cache != null) {
            this.cacheName = cache.getName();
            this.cache = cache;
        } else {
            cacheName = null;
        }

        _afterPropertiesSet();
    }

    /**
     * Spring-bean
     *
     * @param cacheName
     */
    public AbstractCachedModelFactory(String cacheName) {
        super();
        this.cacheName = cacheName;
    }

    private void _afterPropertiesSet() {
        if (cacheName != null && cache == null) {
            if (cm == null) {
                throw new RuntimeException("CacheManager is NULL while cacheName=" + cacheName);
            }

            cache = cm.getCache(cacheName);
            if (cache == null) {
                throw new RuntimeException("Cache with name '" + cacheName + "' not found");
            }
        }

        if (cache != null) {
            final List<CacheLoader> origLoaders = cache.getRegisteredCacheLoaders();
            if (CollectionUtils.isEmpty(origLoaders)) {
                cache.registerCacheLoader(new CacheLoaderDecorator(null));
            } else if (origLoaders.size() == 1 && origLoaders.get(0).getClass().equals(CacheLoaderDecorator.class)) {
                log.debug("CacheLoaderDecorator has been allready set");
            } else {
                log.error("origLoaders:{}", origLoaders);
                throw new RuntimeException("unexpected cache loader");
            }
            // cache.registerCacheLoader(new
            // CacheLoaderDecorator(CollectionUtils.isEmpty(origLoaders) ? null
            // : origLoaders.get(0)));
        } else {
            log.info("cache is disabled");
        }
    }

    @PostConstruct
    private void afterPropertiesSet() {
        _afterPropertiesSet();
    }

    public void invalidate(PK id) {
        if (cache != null) {
            log.debug("invalidate {}/{}", getEntityClass().getSimpleName(), id);
            cache.remove(id);
        }
    }

    public void invalidateLocal(PK id) {
        if (cache != null) {
            log.debug("invalidateLocal {}/{}", getEntityClass().getSimpleName(), id);
            cache.remove(id, true);
        }
    }

    public void invalidate(Collection<PK> ids) {
        if (cache != null) {
            log.debug("invalidateLocal {}/{}", getEntityClass().getSimpleName(), ids);
            cache.removeAll(ids);
        }
    }

    protected abstract Map<PK, ENTITY> fetchEntityByIdAsMap(Collection<PK> ids);

    protected abstract ENTITY fetchEntityById(PK id);

    @Override
    final public ENTITY findEntityById(PK id) {
        if (id == null) {
            return null;
        }

        if (cache == null) {
            return (ENTITY) fetchEntityById(id);
        }

        final Element e = cache.getWithLoader(id, null, _loader);
        if (e == null || e.getObjectValue() == null) {
            return null;
        }

        return (ENTITY) e.getObjectValue();
    }

    @Override
    final public Map<PK, ENTITY> findEntityByIdAsMap(Collection<PK> ids) {
        if (CollectionUtils.isEmpty(ids)) {
            return Collections.emptyMap();
        }

        if (cache == null) {
            if (ids.size() == 1) {
                final PK id = ids.iterator().next();
                return Collections.singletonMap(id, fetchEntityById(id));
            } else {
                return fetchEntityByIdAsMap(ids);
            }
        }

        return (Map) cache.getAllWithLoader(ids, _loader);
    }

    final public CacheManager getCm() {
        return cm;
    }

    final public void setCm(CacheManager cm) {
        this.cm = cm;
    }

    final public Cache getCache() {
        return cache;
    }

    final public void setCache(Cache cache) {
        this.cache = cache;
        _afterPropertiesSet();
    }

    protected void setCreatedAt(ENTITY e) {
        CreatedAtIF.setCreatedAt(e);
    }

    protected void setUpdatedAt(ENTITY e) {
        UpdatedAtIF.setUpdatedAt(e);
    }

}
