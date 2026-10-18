package jp.translatechat;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.ResourcePropertySource;
import org.springframework.mock.env.MockEnvironment;

class CloudConfigurationTest {
    private MockEnvironment cloud() throws IOException {
        var environment = new MockEnvironment();
        environment.getPropertySources().addLast(new ResourcePropertySource(
                new ClassPathResource("application-cloud.properties")));
        return environment;
    }

    @Test
    void marketplaceCredentialsResolveToVerifiedTlsConnection() throws IOException {
        var environment = cloud().withProperty("PGHOST", "test-pooler.example.invalid")
                .withProperty("PGDATABASE", "test_database")
                .withProperty("PGUSER", "test_user")
                .withProperty("PGPASSWORD", "test-only-password");

        String url = environment.getRequiredProperty("spring.datasource.url");
        assertTrue(url.startsWith("jdbc:postgresql://test-pooler.example.invalid/test_database?"));
        assertTrue(url.contains("sslmode=verify-full"));
        assertTrue(url.contains("sslfactory=org.postgresql.ssl.DefaultJavaSSLFactory"));
        assertEquals("test_user", environment.getRequiredProperty("spring.datasource.username"));
        assertEquals("test-only-password", environment.getRequiredProperty("spring.datasource.password"));
    }

    @Test
    void explicitSettingsWorkWithoutMarketplaceVariables() throws IOException {
        var environment = cloud().withProperty("CHAT_DB_URL", "jdbc:postgresql://db:5432/translate_chat")
                .withProperty("CHAT_DB_USER", "chat")
                .withProperty("CHAT_DB_PASSWORD", "local-test-only");

        assertEquals("jdbc:postgresql://db:5432/translate_chat", environment.getRequiredProperty("spring.datasource.url"));
        assertEquals("chat", environment.getRequiredProperty("spring.datasource.username"));
        assertEquals("local-test-only", environment.getRequiredProperty("spring.datasource.password"));
    }
}
