package io.kestra.plugin.datastudio.models;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Member {

    @Schema(title = "Role", description = "OWNER, EDITOR, or VIEWER.")
    private String role;

    @Schema(title = "Member", description = "Member identifier such as `user:ada@acme.com` or `group:analysts@acme.com`.")
    private String member;
}
