/*
 * Copyright 2013-2021 the original author or authors.
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

package org.springframework.cloud.openfeign;

import feign.Client;
import feign.Contract;
import feign.ExceptionPropagationPolicy;
import feign.Feign;
import feign.Logger;
import feign.QueryMapEncoder;
import feign.Request;
import feign.RequestInterceptor;
import feign.Retryer;
import feign.Target.HardCodedTarget;
import feign.codec.Decoder;
import feign.codec.Encoder;
import feign.codec.ErrorDecoder;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.BeanFactoryAware;
import org.springframework.beans.factory.FactoryBean;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.cloud.openfeign.clientconfig.FeignClientConfigurer;
import org.springframework.cloud.openfeign.loadbalancer.FeignBlockingLoadBalancerClient;
import org.springframework.cloud.openfeign.loadbalancer.RetryableFeignBlockingLoadBalancerClient;
import org.springframework.cloud.openfeign.ribbon.LoadBalancerFeignClient;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.core.annotation.AnnotationAwareOrderComparator;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * @author Spencer Gibb
 * @author Venil Noronha
 * @author Eko Kurniawan Khannedy
 * @author Gregor Zurowski
 * @author Matt King
 * @author Olga Maciaszek-Sharma
 * @author Ilia Ilinykh
 * @author Marcin Grzejszczak
 * @author Sam Kruglov
 */
public class FeignClientFactoryBean implements FactoryBean<Object>, InitializingBean, ApplicationContextAware, BeanFactoryAware {

	/***********************************
	 * WARNING! Nothing in this class should be @Autowired. It causes NPEs because of some
	 * lifecycle race condition.
	 ***********************************/

	private static final Log LOG = LogFactory.getLog(FeignClientFactoryBean.class);

	private Class<?> type;

	private String name;

	private String url;

	/**
	 * 服务名称
	 */
	private String contextId;

	private String path;

	private boolean decode404;

	private boolean inheritParentContext = true;

	private ApplicationContext applicationContext;

	private BeanFactory beanFactory;

	private Class<?> fallback = void.class;

	private Class<?> fallbackFactory = void.class;

	private int readTimeoutMillis = new Request.Options().readTimeoutMillis();

	private int connectTimeoutMillis = new Request.Options().connectTimeoutMillis();

	private boolean followRedirects = new Request.Options().isFollowRedirects();

	/**
	 * 附加的 feign builder 自定义
	 */
	private final List<FeignBuilderCustomizer> additionalCustomizers = new ArrayList<>();

	/**
	 * @see InitializingBean#afterPropertiesSet()
	 */
	@Override
	public void afterPropertiesSet() {
		Assert.hasText(contextId, "Context id must be set");
		Assert.hasText(name, "Name must be set");
	}

	/**
	 * @see ApplicationContextAware#setApplicationContext(ApplicationContext)
	 */
	@Override
	public void setApplicationContext(ApplicationContext context) throws BeansException {
		applicationContext = context;
		beanFactory = context;
	}

	/**
	 * @see BeanFactoryAware#setBeanFactory(BeanFactory)
	 */
	@Override
	public void setBeanFactory(BeanFactory beanFactory) throws BeansException {
		this.beanFactory = beanFactory;
	}

	/**
	 * @see FactoryBean#getObjectType()
	 */
	@Override
	public Class<?> getObjectType() {
		return type;
	}

	/**
	 * @see FactoryBean#isSingleton()
	 */
	@Override
	public boolean isSingleton() {
		return true;
	}

	/**
	 * @see FactoryBean#getObject()
	 */
	@Override
	public Object getObject() {
		return this.getTarget();
	}

	/**
	 * @param <T> the target type of the Feign client 目标类型
	 * @return a {@link Feign} client created with the specified data and the context information 包含具体信息和上下文信息的 feign client
	 */
	<T> T getTarget() {
		// 构建 上下文
		FeignContext context = beanFactory != null
			? beanFactory.getBean(FeignContext.class)
			: applicationContext.getBean(FeignContext.class);

		// 构建 Feign.Builder
		Feign.Builder builder = this.feign(context);

		// 一般不会自动指定服务url地址
		if (!StringUtils.hasText(url)) {
			if (url != null && LOG.isInfoEnabled()) {
				LOG.info(
					"The provided URL is empty. Will try picking an instance via load-balancing.");
			} else if (LOG.isDebugEnabled()) {
				LOG.debug("URL not provided. Will use LoadBalancer.");
			}

			// 拼接成 http://credit-service
			if (!name.startsWith("http")) {
				url = "http://" + name;
			} else {
				url = name;
			}
			// path一般也不会设置
			url += this.cleanPath();
			// 负载均衡
			return (T) this.loadBalance(builder, context, new HardCodedTarget<>(type, name, url));
		}

		if (StringUtils.hasText(url) && !url.startsWith("http")) {
			url = "http://" + url;
		}
		String url = this.url + cleanPath();
		Client client = getOptional(context, Client.class);
		if (client != null) {
			if (client instanceof LoadBalancerFeignClient) {
				// not load balancing because we have a url,
				// but ribbon is on the classpath, so unwrap
				client = ((LoadBalancerFeignClient) client).getDelegate();
			}
			if (client instanceof FeignBlockingLoadBalancerClient) {
				// not load balancing because we have a url,
				// but Spring Cloud LoadBalancer is on the classpath, so unwrap
				client = ((FeignBlockingLoadBalancerClient) client).getDelegate();
			}
			if (client instanceof RetryableFeignBlockingLoadBalancerClient) {
				// not load balancing because we have a url,
				// but Spring Cloud LoadBalancer is on the classpath, so unwrap
				client = ((RetryableFeignBlockingLoadBalancerClient) client)
					.getDelegate();
			}
			builder.client(client);
		}
		Targeter targeter = get(context, Targeter.class);
		return (T) targeter.target(this, builder, context,
			new HardCodedTarget<>(type, name, url));
	}

	protected Feign.Builder feign(FeignContext context) {
		// 获取logger
		FeignLoggerFactory loggerFactory = this.get(context, FeignLoggerFactory.class);
		Logger logger = loggerFactory.create(type);

		// @formatter:off
		// 写入默认值
		Feign.Builder builder = this.get(context, Feign.Builder.class)
			// required values
			.logger(logger)
			.encoder(this.get(context, Encoder.class))
			.decoder(this.get(context, Decoder.class))
			.contract(this.get(context, Contract.class));
		// @formatter:on

		// 再对feign进行配置
		this.configureFeign(context, builder);
		// 对builder 进行自定义配置
		this.applyBuildCustomizers(context, builder);

		return builder;
	}

	protected void configureFeign(FeignContext context, Feign.Builder builder) {
		// 获取属性配置
		FeignClientProperties properties = beanFactory != null
			? beanFactory.getBean(FeignClientProperties.class)
			: applicationContext.getBean(FeignClientProperties.class);

		// 获取额外配置器
		FeignClientConfigurer feignClientConfigurer = this.getOptional(context, FeignClientConfigurer.class);
		// 从父context继承
		this.setInheritParentContext(feignClientConfigurer.inheritParentConfiguration());

		if (inheritParentContext) {
			if (properties.isDefaultToProperties()) {
				// 如果是默认的，先使用 Configuration，再使用默认yaml中的配置，最后使用服务自定义的配置
				this.configureUsingConfiguration(context, builder);
				this.configureUsingProperties(properties.getConfig().get(properties.getDefaultConfig()), builder);
				this.configureUsingProperties(properties.getConfig().get(contextId), builder);
			} else {
				this.configureUsingProperties(properties.getConfig().get(properties.getDefaultConfig()), builder);
				this.configureUsingProperties(properties.getConfig().get(contextId), builder);
				this.configureUsingConfiguration(context, builder);
			}
		} else {
			this.configureUsingConfiguration(context, builder);
		}
	}

	private void applyBuildCustomizers(FeignContext context, Feign.Builder builder) {
		Map<String, FeignBuilderCustomizer> customizerMap = context.getInstances(contextId, FeignBuilderCustomizer.class);

		if (customizerMap != null) {
			customizerMap.values().stream()
				.sorted(AnnotationAwareOrderComparator.INSTANCE)
				.forEach(feignBuilderCustomizer -> feignBuilderCustomizer.customize(builder));
		}
		additionalCustomizers.forEach(customizer -> customizer.customize(builder));
	}

	protected void configureUsingConfiguration(FeignContext context,
											   Feign.Builder builder) {
		// 日志等级
		Logger.Level level = this.getInheritedAwareOptional(context, Logger.Level.class);
		if (level != null) {
			builder.logLevel(level);
		}

		// 重试
		Retryer retryer = this.getInheritedAwareOptional(context, Retryer.class);
		if (retryer != null) {
			builder.retryer(retryer);
		}

		// 错误解码器
		ErrorDecoder errorDecoder = this.getInheritedAwareOptional(context, ErrorDecoder.class);
		if (errorDecoder != null) {
			builder.errorDecoder(errorDecoder);
		} else {
			// 错误解码器工厂
			FeignErrorDecoderFactory errorDecoderFactory = getOptional(context, FeignErrorDecoderFactory.class);
			if (errorDecoderFactory != null) {
				ErrorDecoder factoryErrorDecoder = errorDecoderFactory.create(type);
				builder.errorDecoder(factoryErrorDecoder);
			}
		}

		// 请求选项，读超时，链接超时，允许重定向
		Request.Options options = this.getInheritedAwareOptional(context, Request.Options.class);
		if (options != null) {
			builder.options(options);
			readTimeoutMillis = options.readTimeoutMillis();
			connectTimeoutMillis = options.connectTimeoutMillis();
			followRedirects = options.isFollowRedirects();
		}

		// 请求拦截器
		Map<String, RequestInterceptor> requestInterceptors = getInheritedAwareInstances(
			context, RequestInterceptor.class);
		if (requestInterceptors != null) {
			List<RequestInterceptor> interceptors = new ArrayList<>(
				requestInterceptors.values());
			AnnotationAwareOrderComparator.sort(interceptors);
			builder.requestInterceptors(interceptors);
		}

		// 查询map编码器
		QueryMapEncoder queryMapEncoder = this.getInheritedAwareOptional(context,
			QueryMapEncoder.class);
		if (queryMapEncoder != null) {
			builder.queryMapEncoder(queryMapEncoder);
		}

		// 是否启用404
		if (decode404) {
			builder.decode404();
		}

		// 异常策略
		ExceptionPropagationPolicy exceptionPropagationPolicy = this.getInheritedAwareOptional(context, ExceptionPropagationPolicy.class);
		if (exceptionPropagationPolicy != null) {
			builder.exceptionPropagationPolicy(exceptionPropagationPolicy);
		}
	}

	protected void configureUsingProperties(FeignClientProperties.FeignClientConfiguration config,
											Feign.Builder builder) {
		if (config == null) {
			return;
		}

		if (config.getLoggerLevel() != null) {
			builder.logLevel(config.getLoggerLevel());
		}

		connectTimeoutMillis = config.getConnectTimeout() != null
			? config.getConnectTimeout() : connectTimeoutMillis;
		readTimeoutMillis = config.getReadTimeout() != null
			? config.getReadTimeout() : readTimeoutMillis;
		followRedirects = config.isFollowRedirects() != null
			? config.isFollowRedirects() : followRedirects;

		builder.options(new Request.Options(connectTimeoutMillis, TimeUnit.MILLISECONDS,
			readTimeoutMillis, TimeUnit.MILLISECONDS, followRedirects));

		if (config.getRetryer() != null) {
			Retryer retryer = getOrInstantiate(config.getRetryer());
			builder.retryer(retryer);
		}

		if (config.getErrorDecoder() != null) {
			ErrorDecoder errorDecoder = getOrInstantiate(config.getErrorDecoder());
			builder.errorDecoder(errorDecoder);
		}

		if (config.getRequestInterceptors() != null
			&& !config.getRequestInterceptors().isEmpty()) {
			// this will add request interceptor to builder, not replace existing
			for (Class<RequestInterceptor> bean : config.getRequestInterceptors()) {
				RequestInterceptor interceptor = getOrInstantiate(bean);
				builder.requestInterceptor(interceptor);
			}
		}

		if (config.getDecode404() != null) {
			if (config.getDecode404()) {
				builder.decode404();
			}
		}

		if (Objects.nonNull(config.getEncoder())) {
			builder.encoder(getOrInstantiate(config.getEncoder()));
		}

		if (Objects.nonNull(config.getDefaultRequestHeaders())) {
			builder.requestInterceptor(requestTemplate -> requestTemplate
				.headers(config.getDefaultRequestHeaders()));
		}

		if (Objects.nonNull(config.getDefaultQueryParameters())) {
			builder.requestInterceptor(requestTemplate -> requestTemplate
				.queries(config.getDefaultQueryParameters()));
		}

		if (Objects.nonNull(config.getDecoder())) {
			builder.decoder(getOrInstantiate(config.getDecoder()));
		}

		if (Objects.nonNull(config.getContract())) {
			builder.contract(getOrInstantiate(config.getContract()));
		}

		if (Objects.nonNull(config.getExceptionPropagationPolicy())) {
			builder.exceptionPropagationPolicy(config.getExceptionPropagationPolicy());
		}
	}

	private <T> T getOrInstantiate(Class<T> tClass) {
		try {
			return beanFactory != null
				? beanFactory.getBean(tClass)
				: applicationContext.getBean(tClass);
		} catch (NoSuchBeanDefinitionException e) {
			return BeanUtils.instantiateClass(tClass);
		}
	}

	protected <T> T get(FeignContext context, Class<T> type) {
		T instance = context.getInstance(contextId, type);
		if (instance == null) {
			throw new IllegalStateException(
				"No bean found of type " + type + " for " + contextId);
		}
		return instance;
	}

	protected <T> T getOptional(FeignContext context, Class<T> type) {
		return context.getInstance(contextId, type);
	}

	protected <T> T getInheritedAwareOptional(FeignContext context, Class<T> type) {
		if (inheritParentContext) {
			return getOptional(context, type);
		} else {
			return context.getInstanceWithoutAncestors(contextId, type);
		}
	}

	protected <T> Map<String, T> getInheritedAwareInstances(FeignContext context,
															Class<T> type) {
		if (inheritParentContext) {
			return context.getInstances(contextId, type);
		} else {
			return context.getInstancesWithoutAncestors(contextId, type);
		}
	}

	protected <T> T loadBalance(Feign.Builder builder, FeignContext context, HardCodedTarget<T> target) {
		// 创建对应服务的 client
		Client client = this.getOptional(context, Client.class);
		if (client != null) {
			builder.client(client);
			// 获取 Targeter
			Targeter targeter = this.get(context, Targeter.class);
			// 对 client 进行处理， target->硬编码 HardCodedTarget
			return targeter.target(this, builder, context, target);
		}

		throw new IllegalStateException(
			"No Feign Client for loadBalancing defined. Did you forget to include spring-cloud-starter-netflix-ribbon or "
				+ "spring-cloud-starter-loadbalancer?");
	}

	private String cleanPath() {
		String path = this.path.trim();
		if (StringUtils.hasLength(path)) {
			// 开头拼接 /
			if (!path.startsWith("/")) {
				path = "/" + path;
			}
			// 去掉尾部的 /
			if (path.endsWith("/")) {
				path = path.substring(0, path.length() - 1);
			}
		}
		return path;
	}

	public Class<?> getType() {
		return type;
	}

	public void setType(Class<?> type) {
		this.type = type;
	}

	public String getName() {
		return name;
	}

	public void setName(String name) {
		this.name = name;
	}

	public String getContextId() {
		return contextId;
	}

	public void setContextId(String contextId) {
		this.contextId = contextId;
	}

	public String getUrl() {
		return url;
	}

	public void setUrl(String url) {
		this.url = url;
	}

	public String getPath() {
		return path;
	}

	public void setPath(String path) {
		this.path = path;
	}

	public boolean isDecode404() {
		return decode404;
	}

	public void setDecode404(boolean decode404) {
		this.decode404 = decode404;
	}

	public boolean isInheritParentContext() {
		return inheritParentContext;
	}

	public void setInheritParentContext(boolean inheritParentContext) {
		this.inheritParentContext = inheritParentContext;
	}

	public void addCustomizer(FeignBuilderCustomizer customizer) {
		additionalCustomizers.add(customizer);
	}

	public ApplicationContext getApplicationContext() {
		return applicationContext;
	}

	public Class<?> getFallback() {
		return fallback;
	}

	public void setFallback(Class<?> fallback) {
		this.fallback = fallback;
	}

	public Class<?> getFallbackFactory() {
		return fallbackFactory;
	}

	public void setFallbackFactory(Class<?> fallbackFactory) {
		this.fallbackFactory = fallbackFactory;
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) {
			return true;
		}
		if (o == null || getClass() != o.getClass()) {
			return false;
		}
		FeignClientFactoryBean that = (FeignClientFactoryBean) o;
		return Objects.equals(applicationContext, that.applicationContext)
			&& Objects.equals(beanFactory, that.beanFactory)
			&& decode404 == that.decode404
			&& inheritParentContext == that.inheritParentContext
			&& Objects.equals(fallback, that.fallback)
			&& Objects.equals(fallbackFactory, that.fallbackFactory)
			&& Objects.equals(name, that.name) && Objects.equals(path, that.path)
			&& Objects.equals(type, that.type) && Objects.equals(url, that.url)
			&& Objects.equals(connectTimeoutMillis, that.connectTimeoutMillis)
			&& Objects.equals(readTimeoutMillis, that.readTimeoutMillis)
			&& Objects.equals(followRedirects, that.followRedirects);
	}

	@Override
	public int hashCode() {
		return Objects.hash(applicationContext, beanFactory, decode404,
			inheritParentContext, fallback, fallbackFactory, name, path, type, url,
			readTimeoutMillis, connectTimeoutMillis, followRedirects);
	}

	@Override
	public String toString() {
		return new StringBuilder("FeignClientFactoryBean{").append("type=").append(type)
			.append(", ").append("name='").append(name).append("', ").append("url='")
			.append(url).append("', ").append("path='").append(path).append("', ")
			.append("decode404=").append(decode404).append(", ")
			.append("inheritParentContext=").append(inheritParentContext).append(", ")
			.append("applicationContext=").append(applicationContext).append(", ")
			.append("beanFactory=").append(beanFactory).append(", ")
			.append("fallback=").append(fallback).append(", ")
			.append("fallbackFactory=").append(fallbackFactory).append("}")
			.append("connectTimeoutMillis=").append(connectTimeoutMillis).append("}")
			.append("readTimeoutMillis=").append(readTimeoutMillis).append("}")
			.append("followRedirects=").append(followRedirects).append("}")
			.toString();
	}

}
