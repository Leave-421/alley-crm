package com.alleycrm.seed;

import java.io.IOException;
import java.io.InputStream;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/** 클래스패스의 시드 파일을 읽는다. 모르는 칸 · 빠진 칸 · 모르는 enum 값이 있으면 바로 실패한다. */
public final class SeedDataLoader {

    public static final String REGION_STORES = "seed/region-stores.json";

    private static final JsonMapper MAPPER = JsonMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
            .enable(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES)
            .build();

    private SeedDataLoader() {
    }

    public static SeedRegionData loadRegionStores() {
        return load(REGION_STORES);
    }

    static SeedRegionData load(String classpathLocation) {
        try (InputStream in = SeedDataLoader.class.getClassLoader().getResourceAsStream(classpathLocation)) {
            if (in == null) {
                throw new IllegalStateException("시드 파일이 없습니다: " + classpathLocation);
            }
            return MAPPER.readValue(in, SeedRegionData.class);
        } catch (IOException e) {
            throw new IllegalStateException("시드 파일을 읽지 못했습니다: " + classpathLocation, e);
        }
    }

    static SeedRegionData parse(String json) {
        return MAPPER.readValue(json, SeedRegionData.class);
    }
}
