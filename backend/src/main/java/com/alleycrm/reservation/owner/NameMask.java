package com.alleycrm.reservation.owner;

/** 사장님 화면의 가린 이름(예: 김로코 → 김*코, 김코 → 김*). 이름이 없으면 null */
final class NameMask {

    private NameMask() {
    }

    static String mask(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        int[] cps = name.strip().codePoints().toArray();
        if (cps.length == 1) {
            return new String(cps, 0, 1) + "*";
        }
        if (cps.length == 2) {
            return new String(cps, 0, 1) + "*";
        }
        StringBuilder sb = new StringBuilder().appendCodePoint(cps[0]);
        sb.append("*".repeat(cps.length - 2));
        return sb.appendCodePoint(cps[cps.length - 1]).toString();
    }
}
