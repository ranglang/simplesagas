package io.simplesource.saga.serialization.utils;

import org.apache.avro.specific.SpecificRecord;
import org.apache.kafka.common.serialization.Deserializer;
import org.apache.kafka.common.serialization.Serde;
import org.apache.kafka.common.serialization.Serializer;

import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.Function;

public class SerdeUtils {
    public static <S, V> Serde<V> iMap(Serde<S> sSerde, Function<V, S> toGeneric, Function<S, V> fromGeneric) {
        return iMap(sSerde, (topic, v) -> toGeneric.apply(v), (topic, s) -> fromGeneric.apply(s));
    }

    public static <S, V> Serde<V> iMap(Serde<S> sSerde, BiFunction<String, V, S> toGeneric, BiFunction<String, S, V> fromGeneric) {
        Serializer<V> serializer = new Serializer<V>() {
            @Override
            public void configure(Map<String, ?> configs, boolean isKey) {
            }

            @Override
            public byte[] serialize(String topic, V data) {
                return sSerde.serializer().serialize(topic, toGeneric.apply(topic, data));
            }

            @Override
            public void close() {
                sSerde.serializer().close();
            }
        };

        Deserializer<V> deserializer = new Deserializer<V>() {
            @Override
            public void configure(Map<String, ?> configs, boolean isKey) {}

            @Override
            public V deserialize(String topic, byte[] data) {
                S s = sSerde.deserializer().deserialize(topic, data);
                return fromGeneric.apply(topic, s);
            }

            @Override
            public void close() {
                sSerde.deserializer().close();
            }
        };

        return new Serde<V>() {
            @Override
            public void configure(Map<String, ?> configs, boolean isKey) {
                sSerde.configure(configs, isKey);
            }

            @Override
            public void close() {
                sSerde.close();
            }

            @Override
            public Serializer<V> serializer() {
                return serializer;
            }

            @Override
            public Deserializer<V> deserializer() {
                return deserializer;
            }
        };
    }


}
