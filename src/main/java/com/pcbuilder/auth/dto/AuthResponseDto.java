package com.pcbuilder.auth.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
public class AuthResponseDto {

    private String token;
    private String tokenType = "Bearer";
    private UserDto user;

    public AuthResponseDto(String token, UserDto user) {
        this.token = token;
        this.user = user;
    }
}
