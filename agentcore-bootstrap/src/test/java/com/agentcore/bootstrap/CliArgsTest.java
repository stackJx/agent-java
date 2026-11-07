package com.agentcore.bootstrap;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.agentcore.spring.AgentCoreProperties;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.SimpleCommandLinePropertySource;
import org.springframework.core.env.StandardEnvironment;

class CliArgsTest {

    @TempDir
    Path dir;

    @Test
    void explicitWorkingDirectoryBindsToOnePathInsteadOfCommaJoinedDefaults() {
        CliArgs args = CliArgs.parse(new String[]{"serve", "--agentcore.agent.working-directory=" + dir});
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(source(args));
        AgentCoreProperties properties = Binder.get(environment).bind("agentcore", AgentCoreProperties.class).get();
        assertEquals(dir, properties.getAgent().getWorkingDirectory());
        assertEquals(List.of("--agentcore.agent.working-directory=" + dir), args.toSpringArgs());
    }

    @Test
    void explicitSpringPropertiesOverrideShortOptionsWithoutDuplicates() throws Exception {
        Path config = Files.createFile(dir.resolve("config.yml"));
        CliArgs args = CliArgs.parse(new String[]{"serve", "--cwd", ".", "--model", "mock", "--yolo",
                "--no-session", "--port", "8080", "--host", "127.0.0.1", "--config", config.toString(),
                "--agentcore.agent.working-directory=" + dir, "--agentcore.model=custom:model",
                "--agentcore.permissions.mode=readonly", "--agentcore.sessions.enabled=true",
                "--server.port=9090", "--server.address=0.0.0.0", "--spring.config.additional-location=optional:file:other.yml"});
        SimpleCommandLinePropertySource source = source(args);
        assertEquals(dir.toString(), source.getProperty("agentcore.agent.working-directory"));
        assertEquals("custom:model", source.getProperty("agentcore.model"));
        assertEquals("readonly", source.getProperty("agentcore.permissions.mode"));
        assertEquals("true", source.getProperty("agentcore.sessions.enabled"));
        assertEquals("9090", source.getProperty("server.port"));
        assertEquals("0.0.0.0", source.getProperty("server.address"));
        assertEquals("optional:file:other.yml", source.getProperty("spring.config.additional-location"));
    }

    @Test
    void defaultsAndShortOptionsStillWorkAndReadonlyWinsOverYolo() {
        assertEquals(Path.of(".").toAbsolutePath().normalize().toString(),
                source(CliArgs.parse(new String[]{"serve"})).getProperty("agentcore.agent.working-directory"));
        SimpleCommandLinePropertySource source = source(CliArgs.parse(new String[]{"serve", "--cwd", dir.toString(),
                "--port", "8081", "--readonly", "--yolo"}));
        assertEquals(dir.toString(), source.getProperty("agentcore.agent.working-directory"));
        assertEquals("8081", source.getProperty("server.port"));
        assertEquals("readonly", source.getProperty("agentcore.permissions.mode"));
    }

    private SimpleCommandLinePropertySource source(CliArgs args) {
        return new SimpleCommandLinePropertySource(args.toSpringArgs().toArray(String[]::new));
    }
}
