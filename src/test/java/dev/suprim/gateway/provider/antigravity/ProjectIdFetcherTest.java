package dev.suprim.gateway.provider.antigravity;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ProjectIdFetcherTest {

	@Test
	void buildLoadCodeAssistBody_correctStructure() {
		String body = ProjectIdFetcher.buildLoadCodeAssistBody();

		assertTrue(body.contains("\"ideType\":\"VSCODE\""));
		assertTrue(body.contains("\"platform\":\"PLATFORM_UNSPECIFIED\""));
		assertTrue(body.contains("\"pluginType\":\"GEMINI\""));
		assertTrue(body.contains("\"metadata\""));
	}

	@Test
	void parseProjectId_extractsStringProject() {
		String json = """
				{"cloudaicompanionProject":"projects/cloudaicompanion-abc123"}
				""";
		String projectId = ProjectIdFetcher.parseProjectId(json);
		assertEquals("projects/cloudaicompanion-abc123", projectId);
	}

	@Test
	void parseProjectId_extractsObjectProject() {
		String json = """
				{"cloudaicompanionProject":{"id":"projects/cloudaicompanion-obj123"}}
				""";
		String projectId = ProjectIdFetcher.parseProjectId(json);
		assertEquals("projects/cloudaicompanion-obj123", projectId);
	}

	@Test
	void parseProjectId_returnsNullWhenMissing() {
		String json = """
				{"someOtherField":"value"}
				""";
		String projectId = ProjectIdFetcher.parseProjectId(json);
		assertNull(projectId);
	}

	@Test
	void parseOnboardResponse_extractsStringProjectWhenDone() {
		String json = """
				{"done":true,"response":{"cloudaicompanionProject":"projects/cloudaicompanion-xyz"}}
				""";
		String projectId = ProjectIdFetcher.parseOnboardResponse(json);
		assertEquals("projects/cloudaicompanion-xyz", projectId);
	}

	@Test
	void parseOnboardResponse_extractsObjectProjectWhenDone() {
		String json = """
				{"done":true,"response":{"cloudaicompanionProject":{"id":"projects/obj-xyz"}}}
				""";
		String projectId = ProjectIdFetcher.parseOnboardResponse(json);
		assertEquals("projects/obj-xyz", projectId);
	}

	@Test
	void parseOnboardResponse_returnsNullWhenNotDone() {
		String json = """
				{"done":false}
				""";
		String projectId = ProjectIdFetcher.parseOnboardResponse(json);
		assertNull(projectId);
	}
}
