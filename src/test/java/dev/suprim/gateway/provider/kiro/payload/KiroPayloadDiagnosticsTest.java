package dev.suprim.gateway.provider.kiro.payload;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KiroPayloadDiagnosticsTest {

	/**
	 * A healthy request must stay cheap to log. The body dump, the JSON tree walk and the per-tool
	 * schema dump together made one request cost around 1 500 lines, so their absence is asserted
	 * rather than left to review.
	 */
	@Test
	void reportsFixedSizeMeasurementsWithoutDumpingThePayload() {
		Logger logger = (Logger) LoggerFactory.getLogger(KiroPayloadDiagnostics.class);
		Level previous = logger.getLevel();
		ListAppender<ILoggingEvent> appender = new ListAppender<>();
		appender.start();
		logger.setLevel(Level.DEBUG);
		logger.addAppender(appender);
		try {
			ObjectNode root = payload();

			KiroPayloadDiagnostics.log(root);

			String logs = joined(appender);
			assertTrue(logs.contains("[PayloadDebug] summary="));
			assertTrue(logs.contains("toolCount=1"));
			assertTrue(logs.contains("tools=1"));
			assertTrue(logs.contains("maxNameChars=9"));
			assertTrue(logs.contains("maxDescriptionChars=11"));
			assertTrue(logs.contains("namesOver64=0"));

			assertFalse(logs.contains("body="), "the body dump must be gone");
			assertFalse(logs.contains("path=$"), "the tree walk must be gone");
			assertFalse(logs.contains("schema={"), "per-tool schema dump must be gone");
			assertFalse(logs.contains("secret value"));
			assertFalse(logs.contains("arn:secret"));

			assertEquals(2, appender.list.size(),
					"a clean payload costs one summary line and one tools line");
		} finally {
			logger.detachAppender(appender);
			logger.setLevel(previous);
		}
	}

	@Test
	void reportsEachSchemaProblemOnceWhenBothPathsRun() {
		Logger logger = (Logger) LoggerFactory.getLogger(KiroPayloadDiagnostics.class);
		Level previous = logger.getLevel();
		ListAppender<ILoggingEvent> appender = new ListAppender<>();
		appender.start();
		logger.setLevel(Level.DEBUG);
		logger.addAppender(appender);
		try {
			ObjectNode root = payload();
			root.at("/conversationState/currentMessage/userInputMessage/userInputMessageContext/tools/0/toolSpecification/inputSchema/json")
			    .asObject()
			    .putArray("required")
			    .removeAll()
			    .add("absent_property");

			KiroPayloadDiagnostics.log(root);
			KiroPayloadDiagnostics.logInvalidRequest(
					root.toString(),
					"AmazonQ",
					"account",
					"REQUEST_BODY_INVALID",
					"Improperly formed request."
			);

			long problems = appender.list.stream()
			                            .map(ILoggingEvent::getFormattedMessage)
			                            .filter(m -> m.contains(
					                            "suspicious=required-property-missing"))
			                            .count();
			assertEquals(1, problems, "the problem must not be reported twice");
			assertTrue(joined(appender).contains("[PayloadInvalid]"));
		} finally {
			logger.detachAppender(appender);
			logger.setLevel(previous);
		}
	}

	private static String joined(ListAppender<ILoggingEvent> appender) {
		return appender.list.stream()
		                    .map(ILoggingEvent::getFormattedMessage)
		                    .reduce("", (left, right) -> left + "\n" + right);
	}

	@Test
	void correlatesInvalidRequestWithoutLoggingSensitiveValues() {
		Logger logger = (Logger) LoggerFactory.getLogger(KiroPayloadDiagnostics.class);
		Level previous = logger.getLevel();
		ListAppender<ILoggingEvent> appender = new ListAppender<>();
		appender.start();
		logger.setLevel(Level.DEBUG);
		logger.addAppender(appender);
		try {
			ObjectNode root = payload();
			root.put("systemPrompt", "system-secret");
			root.at("/conversationState/currentMessage/userInputMessage")
			    .asObject()
			    .put("content", "system-secret\n\nuser-secret");

			KiroPayloadDiagnostics.logInvalidRequest(
					root.toString(),
					"CodeWhisperer",
					"account",
					"REQUEST_BODY_INVALID",
					"Improperly formed request."
			);

			String logs = joined(appender);
			assertTrue(logs.contains("[PayloadInvalid]"));
			assertTrue(logs.contains("reason=REQUEST_BODY_INVALID"));
			assertTrue(logs.contains("fingerprint="));
			assertTrue(logs.contains("systemPromptInSessionStart=true"));
			assertTrue(logs.contains("toolCount=1"));
			assertFalse(logs.contains("system-secret"));
			assertFalse(logs.contains("user-secret"));
			assertFalse(logs.contains("arn:secret"));
			assertFalse(logs.contains("conversation-secret"));
		} finally {
			logger.detachAppender(appender);
			logger.setLevel(previous);
		}
	}

	private static ObjectNode payload() {
		JsonMapper mapper = new JsonMapper();
		ObjectNode root = mapper.createObjectNode();
		root.put("profileArn", "arn:secret");
		ObjectNode conversation = root.putObject("conversationState");
		conversation.put("conversationId", "conversation-secret");
		conversation.put("agentContinuationId", "continuation-secret");
		ObjectNode user = conversation.putObject("currentMessage")
		                              .putObject("userInputMessage");
		user.put("content", "secret value");
		user.put("modelId", "claude-opus-5");
		user.put("origin", "AI_EDITOR");
		ObjectNode specification = user.putObject("userInputMessageContext")
		                               .putArray("tools")
		                               .addObject()
		                               .putObject("toolSpecification");
		specification.put("name", "read_file");
		specification.put("description", "Read a file");
		ObjectNode schema = specification.putObject("inputSchema").putObject("json");
		schema.put("type", "object");
		schema.putObject("properties").putObject("path").put("type", "string");
		schema.putArray("required").add("path");
		return root;
	}
}
