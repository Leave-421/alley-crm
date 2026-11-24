package com.alleycrm.setting;

import java.math.BigDecimal;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 설정값을 읽는 단 한 곳(NFR-04). 권역에 바꾼 값이 있으면 그 값, 없으면 {@link SettingKey}의 기본값.
 * 설정값을 바꾸면 그 뒤에 생기는 값부터 적용된다. 이미 생긴 값(예: 수수료)은 생길 때 값을 자기 기록에 저장해 둔다.
 */
@Service
public class SettingService {

    private final JdbcTemplate jdbc;

    public SettingService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    public String get(long regionId, SettingKey key) {
        List<String> values = jdbc.queryForList(
                "SELECT value FROM setting WHERE region_id = ? AND setting_key = ?",
                String.class, regionId, key.key());
        return values.isEmpty() ? key.defaultValue() : values.get(0);
    }

    public BigDecimal getDecimal(long regionId, SettingKey key) {
        return new BigDecimal(get(regionId, key));
    }

    public int getInt(long regionId, SettingKey key) {
        return Integer.parseInt(get(regionId, key));
    }

    /** 권역의 설정값을 바꾼다. 값 형식은 기본값과 같은 숫자여야 한다. */
    @Transactional
    public void set(long regionId, SettingKey key, String value) {
        new BigDecimal(value); // 숫자가 아니면 NumberFormatException
        jdbc.update("""
                INSERT INTO setting (region_id, setting_key, value) VALUES (?, ?, ?)
                ON DUPLICATE KEY UPDATE value = VALUES(value)
                """, regionId, key.key(), value);
    }
}
