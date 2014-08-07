/*
 * Copyright 2011-2013 the original author or authors.
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
package org.springframework.data.repository.cdi;

import java.lang.annotation.Annotation;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import javax.enterprise.context.ApplicationScoped;
import javax.enterprise.context.spi.CreationalContext;
import javax.enterprise.inject.Alternative;
import javax.enterprise.inject.Stereotype;
import javax.enterprise.inject.spi.Bean;
import javax.enterprise.inject.spi.BeanManager;
import javax.enterprise.inject.spi.InjectionPoint;
import javax.enterprise.inject.spi.PassivationCapable;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;

/**
 * Base class for {@link Bean} wrappers.
 * 
 * @author Dirk Mahler
 * @author Oliver Gierke
 * @author Mark Paluch
 */
public abstract class CdiRepositoryBean<T> implements Bean<T>, PassivationCapable {

	private static final Logger LOGGER = LoggerFactory.getLogger(CdiRepositoryBean.class);

	private final Set<Annotation> qualifiers;
	private final Class<T> repositoryType;
	private final Bean<?> customImplementationBean;
	private final BeanManager beanManager;
	private final String passivationId;

	private transient T repoInstance;

	/**
	 * Creates a new {@link CdiRepositoryBean}.
	 * 
	 * @param qualifiers must not be {@literal null}.
	 * @param repositoryType has to be an interface must not be {@literal null}.
	 * @param beanManager the CDI {@link BeanManager}, must not be {@literal null}.
	 */
	public CdiRepositoryBean(Set<Annotation> qualifiers, Class<T> repositoryType, BeanManager beanManager) {
		this(qualifiers, repositoryType, beanManager, null);
	}

	/**
	 * Creates a new {@link CdiRepositoryBean}.
	 * 
	 * @param qualifiers must not be {@literal null}.
	 * @param repositoryType has to be an interface must not be {@literal null}.
	 * @param beanManager the CDI {@link BeanManager}, must not be {@literal null}.
	 * @param customImplementationBean the bean for the custom implementation of the
	 *          {@link org.springframework.data.repository.Repository}, can be {@literal null}.
	 */
	public CdiRepositoryBean(Set<Annotation> qualifiers, Class<T> repositoryType, BeanManager beanManager,
			Bean<?> customImplementationBean) {

		Assert.notNull(qualifiers);
		Assert.notNull(beanManager);
		Assert.notNull(repositoryType);
		Assert.isTrue(repositoryType.isInterface());

		this.qualifiers = qualifiers;
		this.repositoryType = repositoryType;
		this.beanManager = beanManager;
		this.customImplementationBean = customImplementationBean;
		this.passivationId = createPassivationId(qualifiers, repositoryType);
	}

	/**
	 * Creates a unique identifier for the given repository type and the given annotations.
	 * 
	 * @param qualifiers must not be {@literal null} or contain {@literal null} values.
	 * @param repositoryType must not be {@literal null}.
	 * @return
	 */
	private final String createPassivationId(Set<Annotation> qualifiers, Class<?> repositoryType) {

		List<String> qualifierNames = new ArrayList<String>(qualifiers.size());

		for (Annotation qualifier : qualifiers) {
			qualifierNames.add(qualifier.annotationType().getName());
		}

		Collections.sort(qualifierNames);

		StringBuilder builder = new StringBuilder(StringUtils.collectionToDelimitedString(qualifierNames, ":"));
		builder.append(":").append(repositoryType.getName());

		return builder.toString();
	}

	/*
	 * (non-Javadoc)
	 * @see javax.enterprise.inject.spi.Bean#getTypes()
	 */
	@SuppressWarnings("rawtypes")
	public Set<Type> getTypes() {

		Set<Class> interfaces = new HashSet<Class>();
		interfaces.add(repositoryType);
		interfaces.addAll(Arrays.asList(repositoryType.getInterfaces()));

		return new HashSet<Type>(interfaces);
	}

	/**
	 * Returns an instance of an the given {@link Bean}.
	 * 
	 * @param bean the {@link Bean} about to create an instance for.
	 * @param type the expected type of the componentn instance created for that {@link Bean}.
	 * @return the actual component instance.
	 */
	@SuppressWarnings("unchecked")
	protected <S> S getDependencyInstance(Bean<S> bean, Class<S> type) {
		CreationalContext<S> creationalContext = beanManager.createCreationalContext(bean);
		return (S) beanManager.getReference(bean, type, creationalContext);
	}

	/**
	 * Forces the initialization of bean target.
	 */
	public final void initialize() {
		create(beanManager.createCreationalContext(this));
	}

	/*
	 * (non-Javadoc)
	 * @see javax.enterprise.context.spi.Contextual#create(javax.enterprise.context.spi.CreationalContext)
	 */
	public final T create(CreationalContext<T> creationalContext) {

		if (this.repoInstance != null) {
			LOGGER.debug("Returning eagerly created CDI repository instance for {}.", repositoryType.getName());
			return this.repoInstance;
		}

		LOGGER.debug("Creating CDI repository bean instance for {}.", repositoryType.getName());
		this.repoInstance = create(creationalContext, repositoryType);
		return repoInstance;
	}

	/*
	 * (non-Javadoc)
	 * @see javax.enterprise.context.spi.Contextual#destroy(java.lang.Object, javax.enterprise.context.spi.CreationalContext)
	 */
	public void destroy(T instance, CreationalContext<T> creationalContext) {

		if (LOGGER.isDebugEnabled()) {
			LOGGER.debug(String.format("Destroying bean instance %s for repository type '%s'.", instance.toString(),
					repositoryType.getName()));
		}

		creationalContext.release();
	}

	/* 
	 * (non-Javadoc)
	 * @see javax.enterprise.inject.spi.Bean#getQualifiers()
	 */
	public Set<Annotation> getQualifiers() {
		return qualifiers;
	}

	/*
	 * (non-Javadoc)
	 * @see javax.enterprise.inject.spi.Bean#getName()
	 */
	public String getName() {
		return repositoryType.getName();
	}

	/*
	 * (non-Javadoc)
	 * @see javax.enterprise.inject.spi.Bean#getStereotypes()
	 */
	public Set<Class<? extends Annotation>> getStereotypes() {

		Set<Class<? extends Annotation>> stereotypes = new HashSet<Class<? extends Annotation>>();

		for (Annotation annotation : repositoryType.getAnnotations()) {
			Class<? extends Annotation> annotationType = annotation.annotationType();
			if (annotationType.isAnnotationPresent(Stereotype.class)) {
				stereotypes.add(annotationType);
			}
		}

		return stereotypes;
	}

	/*
	 * (non-Javadoc)
	 * @see javax.enterprise.inject.spi.Bean#getBeanClass()
	 */
	public Class<?> getBeanClass() {
		return repositoryType;
	}

	/*
	 * (non-Javadoc)
	 * @see javax.enterprise.inject.spi.Bean#isAlternative()
	 */
	public boolean isAlternative() {
		return repositoryType.isAnnotationPresent(Alternative.class);
	}

	/*
	 * (non-Javadoc)
	 * @see javax.enterprise.inject.spi.Bean#isNullable()
	 */
	public boolean isNullable() {
		return false;
	}

	/*
	 * (non-Javadoc)
	 * @see javax.enterprise.inject.spi.Bean#getInjectionPoints()
	 */
	public Set<InjectionPoint> getInjectionPoints() {
		return Collections.emptySet();
	}

	/*
	 * (non-Javadoc)
	 * @see javax.enterprise.inject.spi.Bean#getScope()
	 */
	public Class<? extends Annotation> getScope() {
		return ApplicationScoped.class;
	}

	/* 
	 * (non-Javadoc)
	 * @see javax.enterprise.inject.spi.PassivationCapable#getId()
	 */
	public String getId() {
		return passivationId;
	}

	/**
	 * Creates the actual component instance.
	 * 
	 * @param creationalContext will never be {@literal null}.
	 * @param repositoryType will never be {@literal null}.
	 * @return
	 * @deprecated overide {@link #create(CreationalContext, Class, Object)} instead.
	 */
	@Deprecated
	protected T create(CreationalContext<T> creationalContext, Class<T> repositoryType) {

		Object customImplementation = customImplementationBean == null ? null : beanManager.getReference(
				customImplementationBean, customImplementationBean.getBeanClass(),
				beanManager.createCreationalContext(customImplementationBean));

		return create(creationalContext, repositoryType, customImplementation);
	}

	/**
	 * Creates the actual component instance.
	 * 
	 * @param creationalContext will never be {@literal null}.
	 * @param repositoryType will never be {@literal null}.
	 * @param customImplementation can be {@literal null}.
	 * @return
	 */
	protected T create(CreationalContext<T> creationalContext, Class<T> repositoryType, Object customImplementation) {
		throw new UnsupportedOperationException("You have to implement create(CreationalContext<T>, Class<T>, Object) "
				+ "in order to use custom repository implementations");
	}

	/* 
	 * (non-Javadoc)
	 * @see java.lang.Object#toString()
	 */
	@Override
	public String toString() {
		return String
				.format("JpaRepositoryBean: type='%s', qualifiers=%s", repositoryType.getName(), qualifiers.toString());
	}
}
