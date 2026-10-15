package io.kestra.plugin.datastudio.permissions;

import java.util.List;
import java.util.regex.Pattern;

final class Members {
    private static final Pattern NAMED = Pattern.compile("^(user|group|serviceAccount):\\S+$");
    private static final Pattern DOMAIN = Pattern.compile("^domain:\\S+$");

    private Members() {
    }

    /**
     * The Data Studio types page assigns {@code user:}, {@code group:}, {@code domain:}, and
     * {@code serviceAccount:} to VIEWER and EDITOR, and assigns {@code allUsers} and {@code domain:}
     * to LINK_VIEWER and LINK_EDITOR. One addMembers call has a single role, so those sets cannot be mixed.
     */
    static String grantRole(AssetRole role, LinkRole linkRole, boolean allowLinkSharing, List<String> members) {
        if (members == null || members.isEmpty()) {
            throw new IllegalArgumentException("members must not be empty");
        }
        boolean named = false;
        boolean allUsers = false;
        boolean domain = false;
        for (String member : members) {
            if (member != null && NAMED.matcher(member).matches()) {
                named = true;
                continue;
            }
            if ("allUsers".equals(member)) {
                allUsers = true;
                continue;
            }
            if (member != null && DOMAIN.matcher(member).matches()) {
                domain = true;
                continue;
            }
            throw new IllegalArgumentException("Grant members must use user:, group:, or serviceAccount:. Got: " + member);
        }
        if (linkRole != null) {
            if (!allowLinkSharing) {
                throw new IllegalArgumentException("linkRole requires allowLinkSharing: true");
            }
            if (named) {
                throw new IllegalArgumentException("LINK_VIEWER and LINK_EDITOR apply only to allUsers and domain: members. Named members use role VIEWER or EDITOR.");
            }
            if (!allUsers && !domain) {
                throw new IllegalArgumentException("linkRole requires an allUsers or domain: member");
            }
            return linkRole.name();
        }
        if (allUsers) {
            throw new IllegalArgumentException("allUsers requires allowLinkSharing: true and linkRole LINK_VIEWER or LINK_EDITOR");
        }
        if (domain && !allowLinkSharing) {
            throw new IllegalArgumentException("Sharing with a domain: member requires allowLinkSharing: true");
        }
        return role.name();
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
            String expected = revoke
                ? "Revoke members must use user:, group:, serviceAccount:, domain:, or allUsers. Got: "
                : "Grant members must use user:, group:, or serviceAccount:. Got: ";
            throw new IllegalArgumentException(expected + member);
        }
    }
}
