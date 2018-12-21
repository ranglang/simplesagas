package io.simplesource.saga.model.saga;

import lombok.Value;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Value
public class SagaAction<A> {
    public final UUID actionId;
    public final String actionType;
    public final ActionCommand<A> command;
    public final Optional<ActionCommand<A>> undoCommand;
    public final Set<UUID> dependencies;
    public final ActionStatus status;
    public final Optional<SagaError> error;
}
