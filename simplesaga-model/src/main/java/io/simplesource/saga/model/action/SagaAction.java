package io.simplesource.saga.model.action;

import io.simplesource.saga.model.saga.SagaError;
import lombok.Value;

import java.util.List;
import java.util.Optional;
import java.util.Set;


@Value(staticConstructor = "of")
public class SagaAction<A> {
    public final ActionId actionId;
    public final ActionCommand<A> command;
    public final Optional<ActionCommand<A>> undoCommand;
    public final Set<ActionId> dependencies;
    public final ActionStatus status;
    public final List<SagaError> error;
    public final int retryCount;

    public SagaAction<A> updated(ActionStatus newStatus) {
        return new SagaAction<>(actionId, command, undoCommand, dependencies, newStatus, error, retryCount);
    }
}
