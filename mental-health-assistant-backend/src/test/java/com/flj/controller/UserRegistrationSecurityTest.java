package com.flj.controller;

import com.flj.DTO.command.UserLoginCommandDTO;
import com.flj.common.GlobarExceptionHandler;
import com.flj.mapper.UserMapper;
import com.flj.service.UserService;
import com.flj.util.JwtTokenUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

class UserRegistrationSecurityTest {
    private static final String BODY = """
            {"username":"fixture_user","email":"fixture@example.invalid","password":"fixture-password",
             "confirmPassword":"fixture-password"%s}
            """;
    private final UserMapper mapper = mock(UserMapper.class);
    private final UserService users = new UserService();
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(users, "userMapper", mapper);
        User controller = new User();
        ReflectionTestUtils.setField(controller, "userService", users);
        mvc = MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(new GlobarExceptionHandler()).build();
    }

    @Test
    void publicRegistrationCannotChooseAdminBeforeAnyDatabaseOperation() throws Exception {
        mvc.perform(post("/api/user/add").contentType(MediaType.APPLICATION_JSON).content(BODY.formatted(",\"userType\":2")))
                .andExpect(jsonPath("$.code").value("400"));
        verifyNoInteractions(mapper);
    }

    @Test
    void registrationWithoutRoleCreatesRegularUser() throws Exception {
        when(mapper.selectCount(any())).thenReturn(0L);
        mvc.perform(post("/api/user/add").contentType(MediaType.APPLICATION_JSON).content(BODY.formatted("")))
                .andExpect(jsonPath("$.code").value("200")).andExpect(jsonPath("$.data.userType").value(1));
        var inserted = ArgumentCaptor.forClass(com.flj.entity.User.class);
        verify(mapper).insert(inserted.capture());
        assertEquals(1, inserted.getValue().getUserType());
    }

    @Test
    void existingAdministratorLoginUsesTheStoredRole() {
        when(mapper.selectOne(any())).thenReturn(com.flj.entity.User.builder().id(7L).username("fixture_admin")
                .userType(2).status(1).password(new BCryptPasswordEncoder().encode("fixture-password")).build());
        UserLoginCommandDTO request = new UserLoginCommandDTO();
        request.setUsername("fixture_admin");
        request.setPassword("fixture-password");
        try (var jwt = mockStatic(JwtTokenUtil.class)) {
            jwt.when(() -> JwtTokenUtil.generateToken(7L, "fixture_admin", 2)).thenReturn("fixture-admin-token");
            var response = users.login(request);
            assertEquals("2", response.getRoleType());
            assertEquals("fixture-admin-token", response.getToken());
        }
    }
}
