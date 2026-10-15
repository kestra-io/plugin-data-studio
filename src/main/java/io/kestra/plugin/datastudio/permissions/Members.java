package io.kestra.plugin.datastudio.permissions;

import java.util.List;
import java.util.regex.Pattern;

final class Members {
    private static final Pattern NAMED = Pattern.compile("^(user|group|serviceAccount):\\S+$");
    private static final Pattern DOMAIN = Pattern.compile("^domain:\\S+$");

    private Members() {
    }

    static void validateGrant(List<String> members, boolean allowLinkSharing) {
        validate(members, allowLinkSharing, false);
    }

    static void validateRevoke(List<String> members) {
        validate(members, true, true);
    }

    private static void validate(List<String> members, boolean allowLinkSharing, boolean revoke) {
        if (members == null || members.isEmpty()) {
            throw new IllegalArgumentException("members must not be empty");
        }
        for (String member : members) {
            if (member != null && NAMED.matcher(member).matches()) {
                continue;
            }
            boolean link = member != null && ("allUsers".equals(member) || DOMAIN.matcher(member).matches());
            if (link && allowLinkSharing) {
                continue;
            }
            if (link) {
                throw new IllegalArgumentException("Sharing with " + member + " requires allowLinkSharing: true");
            }
            String action = revoke ? "Revoke" : "Grant";
            throw new IllegalArgumentException(action + " members must use user:, group:, or serviceAccount:. Got: " + member);
        }
    }
}
