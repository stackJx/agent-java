package com.agentcore.spring.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.agentcore.interfaces.web.ModelController;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.FilterType;

class ModelControllerScanningTest {
    @Configuration(proxyBeanMethods = false)
    @ComponentScan(basePackageClasses = ModelController.class, useDefaultFilters = false,
            includeFilters = @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE, classes = ModelController.class))
    static class ScanningApp { }

    @ParameterizedTest
    @ValueSource(strings = {"agentcore.web.enabled=false", "agentcore.web.models.enabled=false"})
    void hostComponentScanningHonorsBothDisableSwitchesWithoutRequiringCatalog(String disabled) {
        new WebApplicationContextRunner().withUserConfiguration(ScanningApp.class)
                .withPropertyValues(disabled).run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(ModelController.class);
                });
    }

    @Test void hostComponentScanningDoesNotActivateModelControllerOutsideServletApplications() {
        new ApplicationContextRunner().withUserConfiguration(ScanningApp.class).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(ModelController.class);
        });
    }
}
