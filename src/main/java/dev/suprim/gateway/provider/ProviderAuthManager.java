package dev.suprim.gateway.provider;

public interface ProviderAuthManager {

	String getProviderName();

	String getDisplayName();

	boolean isConnected();

	String getAccessToken() throws Exception;

	void disconnect();
}
