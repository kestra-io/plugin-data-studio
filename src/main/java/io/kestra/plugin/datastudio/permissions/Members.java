package io.kestra.plugin.datastudio.permissions;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import io.kestra.plugin.datastudio.models.Member;
import io.kestra.plugin.datastudio.models.Permissions;

final class Members {
    private static final Pattern NAMED = Pattern.compile("^(user|group|serviceAccount):\\S+$");
    private static final Pattern DOMAIN = Pattern.compile("^domain:\\S+$");

    private Members() {
    }

    // API: one role per addMembers call; LINK_* roles only for allUsers and domain: members.
    static String grantRole(AssetRole role, LinkRole linkRole, boolean allowLinkSharing, List<String> members) {
        if (members == null || members.isEmpty()) {
            throw new IllegalArgumentException("members must not be empty");
        }
        var named = false;
        var allUsers = false;
        var domain = false;
        for (var member : members) {
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
            var expected = allowLinkSharing
                ? "Grant members must use user:, group:, serviceAccount:, domain:, or allUsers. Got: "
                : "Grant members must use user:, group:, or serviceAccount:. Got: ";
            throw new IllegalArgumentException(expected + member);
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
        if (members == null || members.isEmpty()) {
            throw new IllegalArgumentException("members must not be empty");
        }
        for (var member : members) {
            var valid = member != null
                && (NAMED.matcher(member).matches() || "allUsers".equals(member) || DOMAIN.matcher(member).matches());
            if (!valid) {
                throw new IllegalArgumentException("Revoke members must use user:, group:, serviceAccount:, domain:, or allUsers. Got: " + member);
            }
        }
    }

    static List<Member> flatten(Permissions permissions) {
        var members = new ArrayList<Member>();
        if (permissions == null || permissions.getPermissions() == null) {
            return members;
        }
        permissions.getPermissions().forEach((role, value) -> {
            if (value == null || value.getMembers() == null) {
                return;
            }
            for (var member : value.getMembers()) {
                members.add(Member.builder().role(role).member(member).build());
            }
        });
        return members;
    }

    static Map<String, List<String>> roleMap(Permissions permissions) {
        if (permissions == null || permissions.getPermissions() == null) {
            return Map.of();
        }
        return permissions.getPermissions().entrySet().stream()
            .collect(Collectors.toMap(
                Map.Entry::getKey,
                entry -> entry.getValue() == null || entry.getValue().getMembers() == null ? List.of() : entry.getValue().getMembers()
            ));
    }
}
