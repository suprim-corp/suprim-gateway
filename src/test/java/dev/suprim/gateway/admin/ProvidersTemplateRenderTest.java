package dev.suprim.gateway.admin;

import dev.suprim.gateway.provider.StoredAccount;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockServletContext;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.servlet.DispatcherServlet;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.context.support.StaticWebApplicationContext;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.spring6.templateresolver.SpringResourceTemplateResolver;
import org.thymeleaf.spring6.view.ThymeleafViewResolver;
import org.thymeleaf.templatemode.TemplateMode;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Renders the providers page through Thymeleaf so a broken or renamed fragment
 * reference fails the build instead of only failing in the browser.
 */
class ProvidersTemplateRenderTest {

	private static final String KIRO = "KIRO";

	private String renderProvidersPage(List<StoredAccount> accounts) throws Exception {
		MockServletContext servletContext = new MockServletContext();
		StaticWebApplicationContext applicationContext = new StaticWebApplicationContext();
		applicationContext.setServletContext(servletContext);
		applicationContext.setResourceLoader(new DefaultResourceLoader());
		applicationContext.refresh();

		SpringResourceTemplateResolver resolver = new SpringResourceTemplateResolver();
		resolver.setApplicationContext(applicationContext);
		resolver.setPrefix("classpath:/templates/");
		resolver.setSuffix(".html");
		resolver.setTemplateMode(TemplateMode.HTML);

		SpringTemplateEngine engine = new SpringTemplateEngine();
		engine.setTemplateResolver(resolver);

		ThymeleafViewResolver viewResolver = new ThymeleafViewResolver();
		viewResolver.setApplicationContext(applicationContext);
		viewResolver.setTemplateEngine(engine);
		viewResolver.setCharacterEncoding("UTF-8");

		Map<String, Object> model = new HashMap<>();
		model.put("accounts", accounts);
		model.put("view", "providers");
		model.put("currentPage", "providers");
		model.put("pageTitle", "Providers");

		MockHttpServletRequest request = new MockHttpServletRequest(servletContext, "GET", "/providers");
		request.setAttribute(DispatcherServlet.WEB_APPLICATION_CONTEXT_ATTRIBUTE, applicationContext);
		MockHttpServletResponse response = new MockHttpServletResponse();
		RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request, response));
		try {
			viewResolver.resolveViewName("layout", Locale.ENGLISH).render(model, request, response);
			return response.getContentAsString();
		} finally {
			RequestContextHolder.resetRequestAttributes();
		}
	}

	@Test
	void providersPage_rendersEveryProviderFormFragment() throws Exception {
		String html = renderProvidersPage(List.of());

		assertTrue(html.contains("id=\"kiroForm\""), "Kiro form fragment missing");
		assertTrue(html.contains("id=\"antigravityForm\""), "Antigravity form fragment missing");
		assertTrue(html.contains("id=\"codexForm\""), "Codex form fragment missing");
		assertTrue(html.contains("id=\"xaiForm\""), "xAI form fragment missing");
		assertTrue(html.contains("id=\"deepseekForm\""), "DeepSeek form fragment missing");
		assertTrue(html.contains("id=\"modelsDialog\""), "Models dialog fragment missing");
		assertTrue(html.contains("id=\"addAccountDialog\""), "Add account dialog fragment missing");
	}

	@Test
	void providersPage_rendersRegionDropdownDefaultingToUsEast1() throws Exception {
		String html = renderProvidersPage(List.of());

		assertTrue(html.contains("<select id=\"kiroSsoRegion\""), "Region select missing");
		assertTrue(html.contains("value=\"us-east-1\" selected"), "us-east-1 is not the default region");
		assertTrue(html.contains("value=\"eu-central-1\""), "eu-central-1 option missing");
		assertTrue(html.contains("value=\"ap-southeast-1\""), "ap-southeast-1 option missing");
	}

	@Test
	void providersPage_rendersAccountRowsWhenAccountsExist() throws Exception {
		StoredAccount connected = StoredAccount.builder()
				.provider(KIRO)
				.name("primary")
				.accessToken("token")
				.expiresAt(Instant.now().plusSeconds(3600))
				.build();

		String html = renderProvidersPage(List.of(connected));

		assertTrue(html.contains("primary"), "Account name missing");
		assertTrue(html.contains("Connected"), "Connected status missing");
		assertTrue(html.contains("View Models"), "Models button missing");
		assertTrue(html.contains("/providers/0/rename"), "Rename form action missing");
	}

	@Test
	void providersPage_rendersEmptyStateWhenNoAccounts() throws Exception {
		String html = renderProvidersPage(List.of());

		assertTrue(html.contains("No accounts configured"), "Empty state missing");
	}
}
