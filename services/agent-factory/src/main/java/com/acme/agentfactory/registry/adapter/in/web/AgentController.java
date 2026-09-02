package com.acme.agentfactory.registry.adapter.in.web;

import com.acme.agentfactory.registry.application.AgentSummaryQuery;
import com.acme.agentfactory.registry.application.port.in.ActivateAgentVersionCommand;
import com.acme.agentfactory.registry.application.port.in.ActivateAgentVersionUseCase;
import com.acme.agentfactory.registry.application.port.in.AddAgentVersionCommand;
import com.acme.agentfactory.registry.application.port.in.AddAgentVersionUseCase;
import com.acme.agentfactory.registry.application.port.in.RegisterAgentCommand;
import com.acme.agentfactory.registry.application.port.in.RegisterAgentUseCase;
import com.acme.agentfactory.registry.domain.AgentId;
import com.acme.agentfactory.registry.domain.ModelRef;
import com.acme.agentfactory.registry.domain.SystemPrompt;
import com.acme.agentfactory.registry.domain.ToolName;
import com.acme.agentfactory.registry.domain.VersionNumber;
import com.acme.kernel.arch.AdapterKind;
import com.acme.kernel.arch.InboundAdapter;
import com.acme.kernel.error.NotFoundException;
import com.acme.security.Actors;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The HTTP edge for the agent registry: parse, map, call the port, map back.
 *
 * <p>Depends on the input ports, never on the classes implementing them - the same separation
 * {@code OrderController} demonstrates, and for the same reason: a decision made here would be
 * invisible to any other way of reaching these use cases.
 */
@InboundAdapter(AdapterKind.REST)
@RestController
@RequestMapping("/agents")
public class AgentController {

    private final RegisterAgentUseCase registerAgent;
    private final AddAgentVersionUseCase addAgentVersion;
    private final ActivateAgentVersionUseCase activateAgentVersion;
    private final AgentSummaryQuery summaries;

    public AgentController(
            RegisterAgentUseCase registerAgent,
            AddAgentVersionUseCase addAgentVersion,
            ActivateAgentVersionUseCase activateAgentVersion,
            AgentSummaryQuery summaries) {
        this.registerAgent = registerAgent;
        this.addAgentVersion = addAgentVersion;
        this.activateAgentVersion = activateAgentVersion;
        this.summaries = summaries;
    }

    @PostMapping
    ResponseEntity<RegisterAgentResponse> register(@Valid @RequestBody RegisterAgentRequest request) {
        AgentId id = registerAgent.registerAgent(new RegisterAgentCommand(
                request.name(),
                new ModelRef(request.provider(), request.modelId()),
                toSystemPrompt(request.systemPrompt()),
                toTools(request.tools())));
        return ResponseEntity.created(URI.create("/agents/" + id.value())).body(new RegisterAgentResponse(id.value()));
    }

    @PostMapping("/{agentId}/versions")
    ResponseEntity<AddAgentVersionResponse> addVersion(
            @PathVariable String agentId, @Valid @RequestBody AddAgentVersionRequest request) {
        VersionNumber version = addAgentVersion.addAgentVersion(new AddAgentVersionCommand(
                agentId,
                new ModelRef(request.provider(), request.modelId()),
                toSystemPrompt(request.systemPrompt()),
                toTools(request.tools())));
        return ResponseEntity.created(URI.create("/agents/" + agentId + "/versions/" + version.value()))
                .body(new AddAgentVersionResponse(version.value()));
    }

    @PostMapping("/{agentId}/versions/{version}/activation")
    ResponseEntity<Void> activate(
            @PathVariable String agentId, @PathVariable int version, Authentication authentication) {
        activateAgentVersion.activateVersion(
                new ActivateAgentVersionCommand(agentId, new VersionNumber(version), Actors.from(authentication)));
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{agentId}")
    AgentSummaryQuery.AgentSummary byId(@PathVariable String agentId) {
        return summaries.byId(agentId).orElseThrow(() -> NotFoundException.of("Agent", agentId));
    }

    @GetMapping
    List<AgentSummaryQuery.AgentSummary> list(@RequestParam(defaultValue = "50") int limit) {
        return summaries.list(Math.min(limit, 200));
    }

    private static SystemPrompt toSystemPrompt(String text) {
        return text == null ? SystemPrompt.none() : new SystemPrompt(text);
    }

    private static Set<ToolName> toTools(List<String> names) {
        return names == null ? Set.of() : names.stream().map(ToolName::new).collect(Collectors.toSet());
    }
}
