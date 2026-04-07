package com.nimbusboard.auth.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
@AllArgsConstructor
public class UserDto {
    private String id;
    private String name;
    private String email;
    private String role;
}
