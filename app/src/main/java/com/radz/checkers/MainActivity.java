package com.radz.checkers;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;

import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity implements CheckersView.Listener {

    private static final int BG = Color.rgb(32, 30, 28);
    private static final int ACCENT = Color.rgb(230, 120, 20);

    private final Board board = new Board();
    private final Engine engine = new Engine();
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());

    private CheckersView view;
    private TextView status, score, moves;
    private HorizontalScrollView movesScroll;

    private int playerColour = Board.BLACK;
    private int level = 2;
    private boolean thinking = false;
    private boolean gameOver = false;
    private int gameId = 0;

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        buildUi();
        restore();
    }

    private int dp(float v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);
        root.setPadding(dp(8), dp(12), dp(8), dp(12));

        TextView title = new TextView(this);
        title.setText("Checkers");
        title.setTextColor(ACCENT);
        title.setTextSize(24);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setGravity(Gravity.CENTER);
        root.addView(title);

        status = new TextView(this);
        status.setTextColor(Color.WHITE);
        status.setTextSize(17);
        status.setGravity(Gravity.CENTER);
        status.setPadding(0, dp(6), 0, dp(2));
        root.addView(status);

        score = new TextView(this);
        score.setTextColor(Color.rgb(200, 200, 200));
        score.setTextSize(15);
        score.setGravity(Gravity.CENTER);
        score.setPadding(0, 0, 0, dp(8));
        root.addView(score);

        view = new CheckersView(this);
        view.setBoard(board);
        view.setListener(this);
        root.addView(view, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        movesScroll = new HorizontalScrollView(this);
        moves = new TextView(this);
        moves.setTextColor(Color.rgb(200, 200, 200));
        moves.setTextSize(15);
        moves.setTypeface(Typeface.MONOSPACE);
        moves.setPadding(dp(4), dp(10), dp(4), dp(8));
        moves.setSingleLine(true);
        movesScroll.addView(moves);
        root.addView(movesScroll);

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.setPadding(0, dp(8), 0, 0);
        buttons.addView(button("New", v -> showNewGameDialog()));
        buttons.addView(button("Undo", v -> undo()));
        buttons.addView(button("Hint", v -> hint()));
        buttons.addView(button("Flip", v -> view.setFlipped(!view.isFlipped())));
        root.addView(buttons);

        setContentView(root);
    }

    private Button button(String text, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextColor(Color.WHITE);
        b.setTextSize(16);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.rgb(60, 56, 52));
        bg.setStroke(dp(1), ACCENT);
        bg.setCornerRadius(dp(10));
        b.setBackground(bg);
        b.setOnClickListener(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(52), 1f);
        lp.setMargins(dp(4), 0, dp(4), 0);
        b.setLayoutParams(lp);
        return b;
    }

    private static String name(int colour) { return colour == Board.BLACK ? "Black" : "Red"; }

    // ---------------- game flow ----------------

    private void newGame(int colour, int lvl) {
        gameId++;
        engine.abort();
        thinking = false;
        gameOver = false;
        playerColour = colour;
        level = lvl;
        board.reset();
        view.setBoard(board);
        view.setFlipped(colour == Board.BLACK); // your pieces at the bottom
        afterMove();
    }

    private void applyMove(Board.Move m) {
        board.make(m);
        view.clearSelection();
        afterMove();
    }

    private void afterMove() {
        updateMoves();
        int b = board.count(Board.BLACK), r = board.count(Board.RED);
        score.setText("Black " + b + "  •  Red " + r + "   (you: " + name(playerColour) + ")");
        List<Board.Move> legal = board.legalMoves();
        String result = null;
        if (legal.isEmpty()) {
            result = board.side == playerColour ? name(-playerColour) + " wins — computer wins" : "You win! 🎉";
        } else if (board.quiet >= 80) {
            result = "Draw — 40 moves each without a capture";
        }
        if (result != null) {
            gameOver = true;
            status.setText(result);
            view.setInputEnabled(false);
            view.invalidate();
            save();
            return;
        }
        boolean myTurn = board.side == playerColour;
        view.setInputEnabled(myTurn);
        if (myTurn) {
            status.setText(legal.get(0).isCapture()
                    ? "You must capture! (" + Engine.LEVEL_NAMES[level] + ")"
                    : "Your move (" + Engine.LEVEL_NAMES[level] + ")");
        } else {
            status.setText("Computer is thinking…");
            startComputer();
        }
        view.invalidate();
        save();
    }

    private void startComputer() {
        thinking = true;
        final int id = gameId;
        final int lvl = level;
        final Board snapshot = new Board();
        Engine.copy(board, snapshot);
        worker.execute(() -> {
            long t0 = System.currentTimeMillis();
            Board.Move m = engine.findMove(snapshot, lvl);
            long wait = 400 - (System.currentTimeMillis() - t0);
            if (wait > 0) try { Thread.sleep(wait); } catch (InterruptedException ignored) { }
            ui.post(() -> {
                if (id != gameId) return;
                thinking = false;
                Board.Move real = match(m);
                if (real != null) applyMove(real);
            });
        });
    }

    /** Finds the move on the live board that matches one found on a copy. */
    private Board.Move match(Board.Move m) {
        if (m == null) return null;
        for (Board.Move lm : board.legalMoves())
            if (lm.from == m.from && Arrays.equals(lm.path, m.path)) return lm;
        return null;
    }

    @Override
    public void onUserMove(Board.Move m) {
        if (thinking || gameOver || board.side != playerColour) return;
        applyMove(m);
    }

    private void undo() {
        if (board.ply() == 0) return;
        gameId++;
        engine.abort();
        thinking = false;
        gameOver = false;
        do { board.unmake(); } while (board.ply() > 0 && board.side != playerColour);
        view.clearSelection();
        afterMove();
    }

    private void hint() {
        if (thinking || gameOver || board.side != playerColour) return;
        status.setText("Thinking of a hint…");
        view.setInputEnabled(false);
        final int id = gameId;
        final int plyAt = board.ply();
        final Board snapshot = new Board();
        Engine.copy(board, snapshot);
        thinking = true;
        worker.execute(() -> {
            Board.Move m = new Engine().findMove(snapshot, 3);
            ui.post(() -> {
                if (id != gameId || plyAt != board.ply()) return;
                thinking = false;
                view.setInputEnabled(true);
                Board.Move real = match(m);
                status.setText("Hint: " + (real != null ? Board.notation(real) : "—"));
                view.setHint(real);
            });
        });
    }

    private void updateMoves() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < board.ply(); i++) {
            if (i % 2 == 0) sb.append(i / 2 + 1).append(". ");
            sb.append(Board.notation(board.moveAt(i))).append(' ');
        }
        moves.setText(sb.length() == 0 ? "No moves yet — Black moves first" : sb.toString());
        movesScroll.post(() -> movesScroll.fullScroll(View.FOCUS_RIGHT));
    }

    private void showNewGameDialog() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(8), dp(20), 0);

        TextView l1 = new TextView(this);
        l1.setText("Play as");
        l1.setTypeface(Typeface.DEFAULT_BOLD);
        box.addView(l1);
        RadioGroup colours = new RadioGroup(this);
        String[] cNames = {"Black (moves first)", "Red", "Random"};
        for (int i = 0; i < 3; i++) {
            RadioButton r = new RadioButton(this);
            r.setText(cNames[i]);
            r.setId(100 + i);
            colours.addView(r);
        }
        colours.check(playerColour == Board.BLACK ? 100 : 101);
        box.addView(colours);

        TextView l2 = new TextView(this);
        l2.setText("Difficulty");
        l2.setTypeface(Typeface.DEFAULT_BOLD);
        l2.setPadding(0, dp(12), 0, 0);
        box.addView(l2);
        RadioGroup levels = new RadioGroup(this);
        for (int i = 0; i < Engine.LEVEL_NAMES.length; i++) {
            RadioButton r = new RadioButton(this);
            r.setText(Engine.LEVEL_NAMES[i]);
            r.setId(200 + i);
            levels.addView(r);
        }
        levels.check(200 + level);
        box.addView(levels);

        new AlertDialog.Builder(this)
                .setTitle("New game")
                .setView(box)
                .setPositiveButton("Start", (d, w) -> {
                    int c = colours.getCheckedRadioButtonId() - 100;
                    int colour = c == 0 ? Board.BLACK : c == 1 ? Board.RED : (new Random().nextBoolean() ? Board.BLACK : Board.RED);
                    newGame(colour, levels.getCheckedRadioButtonId() - 200);
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    // ---------------- persistence ----------------

    private void save() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < board.ply(); i++) {
            Board.Move m = board.moveAt(i);
            sb.append(m.from);
            for (int p : m.path) sb.append(',').append(p);
            sb.append(' ');
        }
        getSharedPreferences("checkers", MODE_PRIVATE).edit()
                .putString("moves", sb.toString().trim())
                .putInt("colour", playerColour)
                .putInt("level", level)
                .putBoolean("flipped", view.isFlipped())
                .apply();
    }

    private void restore() {
        SharedPreferences p = getSharedPreferences("checkers", MODE_PRIVATE);
        playerColour = p.getInt("colour", Board.BLACK);
        level = Math.max(0, Math.min(4, p.getInt("level", 2)));
        board.reset();
        String ms = p.getString("moves", "");
        if (!ms.isEmpty()) {
            try {
                for (String tok : ms.split(" ")) {
                    String[] parts = tok.split(",");
                    int from = Integer.parseInt(parts[0]);
                    int[] path = new int[parts.length - 1];
                    for (int i = 1; i < parts.length; i++) path[i - 1] = Integer.parseInt(parts[i]);
                    Board.Move found = null;
                    for (Board.Move lm : board.legalMoves())
                        if (lm.from == from && Arrays.equals(lm.path, path)) { found = lm; break; }
                    if (found == null) break;
                    board.make(found);
                }
            } catch (NumberFormatException ignored) { }
        }
        view.setBoard(board);
        view.setFlipped(p.getBoolean("flipped", playerColour == Board.BLACK));
        afterMove();
    }

    @Override
    protected void onPause() {
        super.onPause();
        save();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        gameId++;
        engine.abort();
        worker.shutdownNow();
    }
}
