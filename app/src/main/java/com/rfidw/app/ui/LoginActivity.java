package com.rfidw.app.ui;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.View;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.textfield.TextInputEditText;
import com.rfidw.app.R;
import com.rfidw.app.auth.UserAccounts;
import com.rfidw.app.auth.UserSession;

public class LoginActivity extends AppCompatActivity {

    private TextInputEditText etUserId;
    private TextInputEditText etPassword;
    private TextView tvLoginError;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        SharedPreferences prefs = getSharedPreferences(UserSession.PREFS_NAME, MODE_PRIVATE);
        if (UserSession.isLoggedIn(prefs)) {
            openMainAndFinish();
            return;
        }

        setContentView(R.layout.activity_login);
        etUserId = findViewById(R.id.etUserId);
        etPassword = findViewById(R.id.etPassword);
        tvLoginError = findViewById(R.id.tvLoginError);
        MaterialButton btnLogin = findViewById(R.id.btnLogin);

        btnLogin.setOnClickListener(v -> attemptLogin(prefs));
        etPassword.setOnEditorActionListener((textView, actionId, event) -> {
            attemptLogin(prefs);
            return true;
        });
    }

    private void attemptLogin(SharedPreferences prefs) {
        String userId = etUserId.getText() != null ? etUserId.getText().toString().trim() : "";
        String password = etPassword.getText() != null ? etPassword.getText().toString() : "";

        if (userId.isEmpty()) {
            showError(getString(R.string.login_error_user_id_required));
            return;
        }
        if (password.isEmpty()) {
            showError(getString(R.string.login_error_password_required));
            return;
        }
        if (!UserAccounts.verify(userId, password)) {
            showError(getString(R.string.login_error_invalid));
            return;
        }

        UserSession.login(prefs, userId);
        openMainAndFinish();
    }

    private void showError(String message) {
        tvLoginError.setText(message);
        tvLoginError.setVisibility(View.VISIBLE);
    }

    private void openMainAndFinish() {
        startActivity(new Intent(this, MainActivity.class));
        finish();
    }
}
