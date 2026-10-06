package com.stockscreener.pipeline.datasource.us;

import org.junit.jupiter.api.Test;

class R16PmBehaviorTest {
    @Test
    void print() throws Exception {
        StringBuilder sb = new StringBuilder();
        for (String n : new String[] {"abc-20180331_LAB.xml", "abc-20180331_lab.xml", "abc-20180331.xml"}) {
            sb.append("R16PM ").append(n).append(" candidate=").append(SecFilingClient.isInstanceCandidate(n))
                    .append(" artifact=").append(SecFilingClient.isXbrlArtifact(n)).append('\n');
        }
        sb.append("R16PM folder [abc-20180331.xml, abc-20180331_LAB.xml] -> ");
        try {
            sb.append(SecFilingSupplement.pickInstance(java.util.List.of("abc-20180331.xml", "abc-20180331_LAB.xml"), "pm"));
        } catch (Exception e) {
            sb.append("throws ").append(e.getClass().getSimpleName());
        }
        sb.append('\n');
        System.out.print(sb);
        String p = System.getenv("R16_PM_OUT");
        if (p != null) {
            java.nio.file.Files.writeString(java.nio.file.Path.of(p), sb.toString());
        }
    }
}
