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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KiroPayloadDiagnosticsTest {

	@Test
	void logsStructureAndLengthsWithoutSensitiveValues() {
		Logger logger = (Logger) LoggerFactory.getLogger(KiroPayloadDiagnostics.class);
		Level previous = logger.getLevel();
		ListAppender<ILoggingEvent> appender = new ListAppender<>();
		appender.start();
		logger.setLevel(Level.DEBUG);
		logger.addAppender(appender);
		try {
			ObjectNode root = payload();

			KiroPayloadDiagnostics.log(root);

			String logs = appender.list.stream()
			                      .map(ILoggingEvent::getFormattedMessage)
			                      .reduce("", (left, right) -> left + "\n" + right);
			assertTrue(logs.contains("[PayloadDebug] body="));
			assertTrue(logs.contains("valueLength=12"));
			assertTrue(logs.contains("schemaType=object"));
			assertTrue(logs.contains("properties=1"));
			assertTrue(logs.contains("required=1"));
			assertTrue(logs.contains("<redacted type=string length=12 bytes=12>"));
			assertFalse(logs.contains("secret value"));
			assertFalse(logs.contains("arn:secret"));
		} finally {
			logger.detachAppender(appender);
			logger.setLevel(previous);
		}
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

			String logs = appender.list.stream()
			                      .map(ILoggingEvent::getFormattedMessage)
			                      .reduce("", (left, right) -> left + "\n" + right);
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
