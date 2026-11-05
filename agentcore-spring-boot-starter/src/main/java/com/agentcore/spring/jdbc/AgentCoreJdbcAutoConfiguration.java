package com.agentcore.spring.jdbc;

import com.agentcore.domain.conversation.repository.ConversationRepository;
import com.agentcore.domain.memory.repository.MemoryRepository;
import com.agentcore.infrastructure.persistence.jdbc.JdbcConversationRepository;
import com.agentcore.infrastructure.persistence.jdbc.JdbcMemoryRepository;
import com.agentcore.infrastructure.persistence.jdbc.JdbcPlatform;
import com.agentcore.infrastructure.persistence.jdbc.JdbcSchema;
import com.agentcore.infrastructure.persistence.jdbc.JdbcStore;
import com.agentcore.spring.AgentCoreAutoConfiguration;
import com.agentcore.spring.AgentCoreProperties;
import java.util.List;
import javax.sql.DataSource;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnSingleCandidate;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * 数据库存储自动装配：设置 {@code agentcore.persistence.jdbc.enabled=true} 且容器里有唯一的 {@link DataSource} 时，
 * 会话与长期记忆改存数据库（替换默认的文件存储）。显式开启，避免在业务库里意外建表。
 * 不依赖 JPA / MyBatis，使用应用已有的数据源与连接池。
 */
@AutoConfiguration(before = AgentCoreAutoConfiguration.class,
        afterName = "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration")
@ConditionalOnBooleanProperty(name = "agentcore.persistence.jdbc.enabled")
@ConditionalOnSingleCandidate(DataSource.class)
@EnableConfigurationProperties(AgentCoreProperties.class)
public class AgentCoreJdbcAutoConfiguration {

    private static final System.Logger LOG = System.getLogger(AgentCoreJdbcAutoConfiguration.class.getName());

    @Bean
    @ConditionalOnMissingBean
    public JdbcStore agentJdbcStore(DataSource dataSource, AgentCoreProperties props) {
        AgentCoreProperties.Jdbc cfg = props.getPersistence().getJdbc();
        JdbcStore store = new JdbcStore(dataSource, JdbcPlatform.parse(cfg.getPlatform()), cfg.getTablePrefix());
        if (!"never".equalsIgnoreCase(cfg.getInitializeSchema())) {
            List<String> created = JdbcSchema.createIfMissing(store);
            if (!created.isEmpty()) {
                LOG.log(System.Logger.Level.INFO, "agentcore: created tables " + created + " (" + store.platform() + ")");
            }
        }
        return store;
    }

    @Bean
    @ConditionalOnMissingBean(ConversationRepository.class)
    public JdbcConversationRepository agentJdbcConversationRepository(JdbcStore store) {
        return new JdbcConversationRepository(store);
    }

    @Bean
    @ConditionalOnMissingBean(MemoryRepository.class)
    @ConditionalOnBooleanProperty(name = "agentcore.memory.enabled", matchIfMissing = true)
    public JdbcMemoryRepository agentJdbcMemoryRepository(JdbcStore store, AgentCoreProperties props) {
        return new JdbcMemoryRepository(store, props.getPersistence().getJdbc().getMemoryScope());
    }
}
