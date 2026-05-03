package com.altrix.orchestrator.domain.port.in;

import java.util.List;

public interface GetProviderConfigsUseCase {
    List<ProviderConfigView> getProviderConfigs();
}
