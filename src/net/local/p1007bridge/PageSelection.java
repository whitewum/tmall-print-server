// SPDX-License-Identifier: GPL-2.0-or-later
package net.local.p1007bridge;

import java.io.IOException;
import java.util.LinkedHashSet;

/** Validates one-based user page ranges and returns unique zero-based indices. */
final class PageSelection {
    static int[] parse(String value, int count, int copies) throws IOException {
        if (count < 1) throw new IOException("文档没有可打印页面");
        if (copies < 1 || copies > 10) throw new IOException("份数必须在 1 到 10 之间");
        String input = value == null ? "" : value.trim();
        if (input.length() > 200) throw new IOException("页码输入过长");
        input = input.replace('，', ',').replaceAll("\\s+", "");
        int limit = 100 / copies;
        if (input.length() == 0) {
            if (count > limit) throw new IOException("文档共有 " + count + " 页，请选择页码，使总打印量不超过 100 页");
            int[] all = new int[count];
            for (int i = 0; i < count; i++) all[i] = i;
            return all;
        }
        LinkedHashSet<Integer> selected = new LinkedHashSet<Integer>();
        for (String item : input.split(",", -1)) {
            if (!item.matches("[0-9]+(?:-[0-9]+)?"))
                throw new IOException("页码格式无效，请填写 2、1-3 或 1,3-5");
            String[] ends = item.split("-", -1);
            int first, last;
            try {
                first = Integer.parseInt(ends[0]);
                last = ends.length == 2 ? Integer.parseInt(ends[1]) : first;
            } catch (NumberFormatException invalid) { throw new IOException("页码数值无效"); }
            if (first < 1 || last < 1 || first > count || last > count)
                throw new IOException("页码超出范围，这份文档共有 " + count + " 页");
            if (first > last) throw new IOException("页码范围应从小到大，例如 2-5");
            for (int page = first; page <= last; page++) {
                selected.add(page - 1);
                if (selected.size() > limit) throw new IOException("所选页数乘份数不能超过 100 页");
                if (page == Integer.MAX_VALUE) break;
            }
        }
        int[] result = new int[selected.size()]; int i = 0;
        for (Integer page : selected) result[i++] = page;
        return result;
    }
}
