package com.ktb.chatapp.dto;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;

class FetchMessagesRequestTest {

    @ParameterizedTest
    @MethodSource("limitCases")
    void limitNormalizesInvalidValuesAndCapsMaximum(Integer requestedLimit, int expectedLimit) {
        FetchMessagesRequest request = new FetchMessagesRequest("room-1", requestedLimit, null);

        assertThat(request.limit(FetchMessagesRequest.DEFAULT_LIMIT)).isEqualTo(expectedLimit);
    }

    private static Stream<Arguments> limitCases() {
        return Stream.of(
                Arguments.of(null, 30),
                Arguments.of(0, 30),
                Arguments.of(-1, 30),
                Arguments.of(30, 30),
                Arguments.of(100, 100),
                Arguments.of(101, 100)
        );
    }
}
