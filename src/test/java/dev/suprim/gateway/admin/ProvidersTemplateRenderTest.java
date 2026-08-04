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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Renders the providers page through Thymeleaf so a broken or renamed fragment
 * reference fails the build instead of only failing in the browser.
 */
class ProvidersTemplateRenderTest {

	private static final String KIRO = "KIRO";

	private String renderProvidersPage(List<StoredAccount> accounts) throws Exception {
		return renderProvidersPage(accounts, null);
	}

	private String renderProvidersPage(
			List<StoredAccount> accounts,
			String providerFilter
	) throws Exception {
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

		List<ProviderAccountCard> allCards = ProviderAccountCards.sorted(accounts);
		List<ProviderAccountCard> cards = ProviderAccountCards.filtered(allCards, providerFilter);
		Map<String, Object> model = new HashMap<>();
		model.put("accounts", cards);
		model.put("providerNames", ProviderAccountCards.providers(allCards));
		model.put("providerFilter", providerFilter);
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
	void providersPage_shipsAHiddenRejectionBadgeForTheUsagePollerToReveal() throws Exception {
		StoredAccount connected = StoredAccount.builder()
				.provider(KIRO)
				.name("primary")
				.accessToken("token")
				.expiresAt(Instant.now().plusSeconds(3600))
				.build();

		String html = renderProvidersPage(List.of(connected));

		assertTrue(
				html.contains("card-badge-connected"),
				"Poller cannot hide the connected badge without a hook to find it by"
		);
		assertTrue(
				html.contains("card-badge-unauthorized hidden"),
				"Rejection badge must ship hidden: nothing has asked upstream yet"
		);
		assertTrue(html.contains("Unauthorized"), "Rejection badge text missing");
	}

	@Test
	void providersPage_omitsBothLiveBadgesForADisconnectedAccount() throws Exception {
		StoredAccount noToken = StoredAccount.builder()
				.provider(KIRO)
				.name("stale")
				.build();

		String html = renderProvidersPage(List.of(noToken));

		assertTrue(html.contains("Disconnected"), "Disconnected status missing");
		assertTrue(
				!html.contains("card-badge-unauthorized"),
				"An account that never polls cannot be flipped to rejected"
		);
	}

	@Test
	void providersPage_rendersEmptyStateWhenNoAccounts() throws Exception {
		String html = renderProvidersPage(List.of());

		assertTrue(html.contains("No accounts configured"), "Empty state missing");
	}

	@Test
	void providersPage_loadsUsageHelpersBeforeTheirCallers() throws Exception {
		String html = renderProvidersPage(List.of());

		int usage = html.indexOf("/js/providers-usage.js");
		int dialog = html.indexOf("/js/providers-models-dialog.js");
		int cards = html.indexOf("/js/providers-cards.js");
		int addAccount = html.indexOf("/js/providers-add-account.js");

		assertTrue(usage >= 0, "providers-usage.js not loaded");
		assertTrue(dialog >= 0, "providers-models-dialog.js not loaded");
		assertTrue(cards >= 0, "providers-cards.js not loaded");
		assertTrue(addAccount >= 0, "providers-add-account.js not loaded");
		assertTrue(usage < dialog && usage < cards,
				"providers-usage.js must load before the files calling summarizeUsage");
		assertTrue(html.indexOf("/js/providers-filter.js") >= 0, "providers-filter.js not loaded");
	}

	@Test
	void providersPage_rendersOneCardPerAccountInAGrid() throws Exception {
		StoredAccount kiro = StoredAccount.builder()
				.provider(KIRO)
				.name("primary")
				.accessToken("token")
				.expiresAt(Instant.now().plusSeconds(3600))
				.build();
		StoredAccount antigravity = StoredAccount.builder()
				.provider("ANTIGRAVITY")
				.name("alviss")
				.accessToken("token")
				.build();

		String html = renderProvidersPage(List.of(kiro, antigravity));

		assertTrue(html.contains("md:grid-cols-2"), "Grid columns missing");
		assertEquals(2, countOccurrences(html, "account-card"), "Expected one card per account");
		assertTrue(html.contains("data-index=\"0\""), "First card index missing");
		assertTrue(html.contains("data-index=\"1\""), "Second card index missing");
		assertTrue(html.contains("/providers/1/delete"), "Second card delete action missing");
	}

	@Test
	void providersPage_ordersCardsByProviderNameKeepingStoreIndexes() throws Exception {
		StoredAccount kiro = StoredAccount.builder()
				.provider(KIRO)
				.name("kiro-one")
				.accessToken("token")
				.build();
		StoredAccount antigravity = StoredAccount.builder()
				.provider("ANTIGRAVITY")
				.name("ag-one")
				.accessToken("token")
				.build();

		String html = renderProvidersPage(List.of(kiro, antigravity));

		assertTrue(
				html.indexOf("ag-one") < html.indexOf("kiro-one"),
				"Antigravity should sort before Kiro"
		);
		// The Antigravity card renders first but still addresses store position 1.
		assertTrue(
				html.indexOf("data-index=\"1\"") < html.indexOf("data-index=\"0\""),
				"Cards must keep their credential store index after sorting"
		);
	}

	@Test
	void providersPage_rendersOneFilterOptionPerDistinctProvider() throws Exception {
		StoredAccount kiroA = StoredAccount.builder()
				.provider(KIRO)
				.name("kiro-a")
				.accessToken("token")
				.build();
		StoredAccount kiroB = StoredAccount.builder()
				.provider(KIRO)
				.name("kiro-b")
				.accessToken("token")
				.build();
		StoredAccount codex = StoredAccount.builder()
				.provider("CODEX")
				.name("codex-a")
				.accessToken("token")
				.build();

		String html = renderProvidersPage(List.of(kiroA, kiroB, codex));

		assertTrue(html.contains("id=\"providerFilterSelect\""), "Filter dropdown missing");
		// Counted within the filter's own markup: the add-account dialog has a region select whose
		// options would otherwise be mixed into the tally.
		String filter = filterSelectMarkup(html);
		assertEquals(
				3,
				countOccurrences(filter, "<option"),
				"Expected an All option plus one per distinct provider"
		);
		assertTrue(filter.contains("value=\"CODEX\""), "Codex option missing");
		assertTrue(filter.contains("value=\"KIRO\""), "Kiro option missing");
		assertTrue(html.contains("data-provider=\"KIRO\""), "Card provider marker missing");
	}

	@Test
	void providersPage_preselectsTheRequestedProvider() throws Exception {
		StoredAccount kiro = StoredAccount.builder()
				.provider(KIRO)
				.name("kiro-a")
				.accessToken("token")
				.build();
		StoredAccount codex = StoredAccount.builder()
				.provider("CODEX")
				.name("codex-a")
				.accessToken("token")
				.build();

		String filter = filterSelectMarkup(renderProvidersPage(List.of(kiro, codex), KIRO));

		assertTrue(
				filter.contains("value=\"KIRO\" selected"),
				"Requested provider should render as the selected option"
		);
		assertTrue(
				!filter.contains("value=\"CODEX\" selected"),
				"Only the requested provider should be selected"
		);
	}

	@Test
	void providersPage_rendersOnlyTheRequestedProviderAccounts() throws Exception {
		StoredAccount kiro = StoredAccount.builder()
				.provider(KIRO)
				.name("kiro-a")
				.accessToken("token")
				.build();
		StoredAccount codex = StoredAccount.builder()
				.provider("CODEX")
				.name("codex-a")
				.accessToken("token")
				.build();

		String html = renderProvidersPage(List.of(kiro, codex), KIRO);

		assertTrue(html.contains("kiro-a"), "Requested provider account missing");
		assertTrue(!html.contains("codex-a"), "Unrequested provider account should not render");
	}

	@Test
	void providersPage_omitsFilterWhenThereAreNoAccounts() throws Exception {
		String html = renderProvidersPage(List.of());

		assertTrue(
				!html.contains("id=\"providerFilterSelect\""),
				"Filter dropdown should be hidden with no accounts"
		);
	}

	@Test
	void providersPage_marksDisconnectedCardUsageAsSkipped() throws Exception {
		StoredAccount disconnected = StoredAccount.builder()
				.provider("CODEX")
				.name("stale")
				.build();

		String html = renderProvidersPage(List.of(disconnected));

		assertTrue(html.contains("Disconnected"), "Disconnected status missing");
		assertTrue(html.contains("card-usage mt-3 min-h-[2.25rem] invisible"),
				"Disconnected account should not reserve a usage slot to fetch");
		assertTrue(html.contains("/auth/codex"), "Codex reconnect link missing");
	}

	/**
	 * Just the provider filter's own {@code <select>}, so assertions about its options are not
	 * confused by the region select in the add-account dialog.
	 */
	private static String filterSelectMarkup(String html) {
		int start = html.indexOf("id=\"providerFilterSelect\"");
		if (start < 0) {
			return "";
		}
		int end = html.indexOf("</select>", start);
		String markup = end < 0 ? html.substring(start) : html.substring(start, end);
		// Each attribute sits on its own line in the template, so collapse whitespace to assert on
		// attribute pairs without depending on the source's line breaks.
		return markup.replaceAll("\\s+", " ");
	}

	private static int countOccurrences(String haystack, String needle) {
		int count = 0;
		int from = 0;
		while (true) {
			int at = haystack.indexOf(needle, from);
			if (at < 0) {
				return count;
			}
			count++;
			from = at + needle.length();
		}
	}
}
