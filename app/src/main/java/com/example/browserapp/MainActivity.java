package com.example.browserapp;

import android.app.Activity;
import android.os.Bundle;
import android.graphics.Color;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.TextView;
import java.util.Random;

public class MainActivity extends Activity {
    private int score = 0;
    private final Random random = new Random();
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(com.example.browserapp.R.layout.activity_main);
        FrameLayout game = findViewById(R.id.game);
        TextView scoreText = findViewById(R.id.score);
        Button cube = findViewById(R.id.cube);
        cube.setOnClickListener(v -> {
            score++;
            scoreText.setText("Score: " + score);
            FrameLayout.LayoutParams p = (FrameLayout.LayoutParams) cube.getLayoutParams();
            p.gravity = Gravity.TOP | Gravity.START;
            int maxX = Math.max(1, game.getWidth() - cube.getWidth());
            int maxY = Math.max(1, game.getHeight() - cube.getHeight() - 120);
            p.leftMargin = random.nextInt(maxX);
            p.topMargin = 100 + random.nextInt(Math.max(1, maxY - 100));
            cube.setLayoutParams(p);
            cube.setBackgroundTintList(android.content.res.ColorStateList.valueOf(Color.rgb(70 + random.nextInt(150), 70 + random.nextInt(120), 150 + random.nextInt(100))));
        });
    }
}
