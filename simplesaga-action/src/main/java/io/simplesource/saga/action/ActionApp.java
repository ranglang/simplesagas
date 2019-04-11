package io.simplesource.saga.action;

import io.simplesource.saga.action.app.ActionAppContext;
import io.simplesource.saga.action.app.ActionProcessorBuildStep;
import io.simplesource.saga.model.serdes.ActionSerdes;
import io.simplesource.saga.model.specs.ActionSpec;
import io.simplesource.saga.shared.kafka.PropertiesBuilder;
import io.simplesource.saga.shared.streams.*;

/**
 * An ActionApp is the main component in an KStream action processor application.
 *
 * An action processor application consists of one or more action processors.
 *
 * To create an action processor, we provide an implementation of the functional interface {@link ActionProcessorBuildStep}
 *
 * In this implementation we define both the instructions for building the stream topology, and the details about the topics
 * that are required for this action processor (Note that each action processor uses its own set of topics)
 *
 * For examples of action processor implementations:
 * {@link io.simplesource.saga.action.eventsourcing.EventSourcingBuilder Event Sourcing Action processor}
 * {@link io.simplesource.saga.action.async.AsyncBuilder  Async Action processor}
 *
 * It is recommended to use this pattern to create action processors. {@link ActionProcessorBuildStep} implementations can also be
 * used to provide implementations of other sub-topologies that are not directly related to action processors.
 * This can be useful for transforming stream data that might be used by an action processor, and making this transformation part
 * of the stream topology.
 *
 * @param <A> The action command type (shared across all actions)
 *
 * @see io.simplesource.saga.action.ActionApp
 */
public class ActionApp<A> {

    StreamApp<ActionSpec<A>> streamApp;

    private ActionApp(ActionSpec<A> streamAppInput) {
        streamApp = new StreamApp<>(streamAppInput);
    }

    /**
     *
     * @param serdes - the Serdes for the action topics
     * @param <A> The action command type (shared across all actions)
     * @return the ActionApp
     */
    public static <A> ActionApp<A> of(ActionSerdes<A> serdes) {
        return new ActionApp<>(ActionSpec.of(serdes));
    }

    /**
     *
     * Adds an action processor to the application
     *
     * @param processorBuildStep
     * @return this
     */
    public ActionApp<A> withActionProcessor(ActionProcessorBuildStep<A> processorBuildStep) {
        streamApp.withBuildStep(a ->
                processorBuildStep.applyStep(ActionAppContext.of(a.appInput, a.properties)));
        return this;
    }

    /**
     * Builds the stream.
     *
     * This creates the stream topology and the topic definitions, but doesn't create the topics or run the application
     *
     * @param properties a functional interface for setting or overriding the Kafka properties
     * @return a structure with the result of the build
     */
    public StreamBuildResult build(PropertiesBuilder.BuildSteps properties) {
        return streamApp.build(properties);
    }

    @Deprecated
    public void run(StreamAppConfig appConfig) {
        streamApp.run(appConfig);
    }


    /**
     * Builds and runs the stream.
     *
     * This creates the stream topology and the topic definitions, then create the topics and run the application
     *
     * @param properties a functional interface for setting or overriding the Kafka properties
     */
    public void run(PropertiesBuilder.BuildSteps properties) {
        streamApp.run(properties);
    }
}