package com.alleycrm.seed;

import com.alleycrm.recommend.domain.PublicStatKind;
import com.alleycrm.recommend.domain.TimeBand;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 공공데이터 기본값 파일 {@code seed/public-stats.csv}(E2 #16)를 읽고 검사한다. 파일은 {@code tools/public-data/preprocess.mjs}가
 * 원본에서 만든다. 하나라도 어긋나면 아무것도 넣지 않도록 적재 전에 실패한다.
 */
public final class PublicStatFile {

    public static final String LOCATION = "seed/public-stats.csv";
    static final String HEADER = "kind,category_from,category_to,slot,gender,age_group,value,source,period";

    private static final Set<String> CATEGORIES = Arrays.stream(SeedCategory.values()).map(SeedCategory::code)
            .collect(Collectors.toSet());
    private static final Set<String> DAYS = Set.of("mon", "tue", "wed", "thu", "fri", "sat", "sun");
    private static final Set<String> GENDERS = Set.of("female", "male");
    private static final Set<String> AGE_GROUPS = Set.of("age_20_or_under", "age_30s", "age_40s", "age_50_or_over");
    private static final double SHARE_TOLERANCE = 1e-4;

    /** 한 행. 쓰지 않는 칸은 null. */
    public record Row(PublicStatKind kind, String categoryFrom, String categoryTo, String slot, String gender,
            String ageGroup, BigDecimal value, String source, String period) {
    }

    private PublicStatFile() {
    }

    public static List<Row> load() {
        try (InputStream in = PublicStatFile.class.getClassLoader().getResourceAsStream(LOCATION)) {
            if (in == null) {
                throw new IllegalStateException("공공데이터 파일이 없습니다: " + LOCATION);
            }
            return parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new IllegalStateException("공공데이터 파일을 읽지 못했습니다: " + LOCATION, e);
        }
    }

    static List<Row> parse(String text) {
        List<String> lines = text.lines().filter(l -> !l.isBlank()).toList();
        if (lines.isEmpty() || !lines.get(0).replace("﻿", "").equals(HEADER)) {
            throw new IllegalStateException("공공데이터 파일 머리글이 다릅니다. 기대: " + HEADER);
        }
        List<Row> rows = new ArrayList<>();
        for (int i = 1; i < lines.size(); i++) {
            rows.add(row(splitCsv(lines.get(i)), i + 1));
        }
        validateShares(rows);
        return rows;
    }

    private static Row row(List<String> c, int lineNo) {
        if (c.size() != 9) {
            throw invalid(lineNo, "칸 수가 9가 아닙니다: " + c.size());
        }
        PublicStatKind kind = PublicStatKind.fromCode(c.get(0)).orElseThrow(() -> invalid(lineNo, "모르는 kind " + c.get(0)));
        String from = blankToNull(c.get(1));
        String to = blankToNull(c.get(2));
        String slot = blankToNull(c.get(3));
        String gender = blankToNull(c.get(4));
        String age = blankToNull(c.get(5));
        BigDecimal value;
        try {
            value = new BigDecimal(c.get(6));
        } catch (NumberFormatException e) {
            throw invalid(lineNo, "숫자가 아닌 value " + c.get(6));
        }
        if (value.signum() < 0) {
            throw invalid(lineNo, "음수 value");
        }
        if (to == null || !CATEGORIES.contains(to)) {
            throw invalid(lineNo, "모르는 category_to " + to);
        }
        boolean ok = switch (kind) {
            case CLQ -> from != null && CATEGORIES.contains(from) && slot == null && gender == null && age == null;
            case ATTRACTIVENESS -> from == null && slot == null && gender == null && age == null;
            case DAY_SHARE -> from == null && DAYS.contains(slot) && gender == null && age == null;
            case TIME_BAND_SHARE -> from == null && slot != null && TimeBand.fromCode(slot).isPresent() && gender == null && age == null;
            case GENDER_SHARE -> from == null && slot == null && GENDERS.contains(gender) && age == null;
            case AGE_SHARE -> from == null && slot == null && gender == null && AGE_GROUPS.contains(age);
        };
        if (!ok) {
            throw invalid(lineNo, kind.code() + " 행의 칸 조합이 맞지 않습니다");
        }
        if (c.get(7).isBlank() || c.get(8).isBlank()) {
            throw invalid(lineNo, "출처 · 기준 기간이 비어 있습니다(NFR-10)");
        }
        return new Row(kind, from, to, slot, gender, age, value, c.get(7), c.get(8));
    }

    /** 비중은 같은 묶음 안에서 합이 1이다(매력도는 전체, 나머지는 업종마다). */
    private static void validateShares(List<Row> rows) {
        Map<String, Double> sums = new HashMap<>();
        for (Row r : rows) {
            if (r.kind() == PublicStatKind.CLQ) {
                continue;
            }
            String group = r.kind() == PublicStatKind.ATTRACTIVENESS ? r.kind().code() : r.kind().code() + "/" + r.categoryTo();
            sums.merge(group, r.value().doubleValue(), Double::sum);
        }
        sums.forEach((group, sum) -> {
            if (Math.abs(sum - 1.0) > SHARE_TOLERANCE) {
                throw new IllegalStateException("비중 합이 1이 아닙니다: " + group + " = " + sum);
            }
        });
    }

    /** 따옴표 · 따옴표 안 쉼표를 처리한다. */
    static List<String> splitCsv(String line) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char ch = line.charAt(i);
            if (quoted) {
                if (ch == '"' && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    cur.append('"');
                    i++;
                } else if (ch == '"') {
                    quoted = false;
                } else {
                    cur.append(ch);
                }
            } else if (ch == '"') {
                quoted = true;
            } else if (ch == ',') {
                out.add(cur.toString());
                cur.setLength(0);
            } else {
                cur.append(ch);
            }
        }
        out.add(cur.toString());
        return out;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }

    private static IllegalStateException invalid(int lineNo, String message) {
        return new IllegalStateException(LOCATION + " " + lineNo + "번째 줄: " + message);
    }
}
