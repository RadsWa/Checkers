package com.radz.checkers;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

/** Draws the checkers board and handles tap-to-move input. */
public class CheckersView extends View {

    public interface Listener { void onUserMove(Board.Move m); }

    private static final int LIGHT = Color.rgb(240, 217, 181);
    private static final int DARK = Color.rgb(120, 82, 52);
    private static final int SEL = Color.argb(160, 255, 140, 0);
    private static final int LAST = Color.argb(110, 255, 210, 0);
    private static final int HINT = Color.argb(140, 40, 170, 255);
    private static final int MUST = Color.argb(150, 230, 40, 40);

    private Board board;
    private boolean flipped;
    private boolean inputEnabled = true;
    private int selected = -1;
    private final List<Board.Move> selMoves = new ArrayList<>();
    private Board.Move hint;
    private Listener listener;

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint crown = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint num = new Paint(Paint.ANTI_ALIAS_FLAG);
    private float cell;

    public CheckersView(Context c) {
        super(c);
        crown.setTextAlign(Paint.Align.CENTER);
        crown.setTypeface(Typeface.DEFAULT_BOLD);
        num.setColor(Color.argb(120, 255, 255, 255));
    }

    public void setBoard(Board b) { board = b; clearSelection(); }
    public void setListener(Listener l) { listener = l; }
    public void setFlipped(boolean f) { flipped = f; invalidate(); }
    public boolean isFlipped() { return flipped; }
    public void setInputEnabled(boolean e) { inputEnabled = e; if (!e) clearSelection(); }
    public void setHint(Board.Move m) { hint = m; invalidate(); }
    public void clearSelection() { selected = -1; selMoves.clear(); hint = null; invalidate(); }

    @Override
    protected void onMeasure(int wSpec, int hSpec) {
        int w = MeasureSpec.getSize(wSpec);
        int h = MeasureSpec.getSize(hSpec);
        int size = MeasureSpec.getMode(hSpec) == MeasureSpec.UNSPECIFIED ? w : Math.min(w, h);
        setMeasuredDimension(size, size);
    }

    private int squareAt(int col, int row) {
        return flipped ? (7 - row) * 8 + (7 - col) : row * 8 + col;
    }
    private float sx(int s) { return (flipped ? 7 - (s & 7) : (s & 7)) * cell; }
    private float sy(int s) { return (flipped ? 7 - (s >> 3) : (s >> 3)) * cell; }

    private void shade(Canvas c, int s, int colour) {
        fill.setColor(colour);
        c.drawRect(sx(s), sy(s), sx(s) + cell, sy(s) + cell, fill);
    }

    @Override
    protected void onDraw(Canvas c) {
        if (board == null) return;
        cell = getWidth() / 8f;
        for (int s = 0; s < 64; s++) {
            boolean dark = ((s >> 3) + (s & 7)) % 2 == 1;
            shade(c, s, dark ? DARK : LIGHT);
        }
        Board.Move last = board.lastMove();
        if (last != null) {
            shade(c, last.from, LAST);
            for (int p : last.path) shade(c, p, LAST);
        }
        if (hint != null) {
            shade(c, hint.from, HINT);
            shade(c, hint.to, HINT);
        }
        // When a capture is compulsory, outline the pieces that can capture
        List<Board.Move> legal = inputEnabled ? board.legalMoves() : new ArrayList<>();
        boolean forced = !legal.isEmpty() && legal.get(0).isCapture();
        if (forced && selected < 0) {
            for (Board.Move m : legal) shade(c, m.from, MUST);
        }
        if (selected >= 0) shade(c, selected, SEL);

        // square numbers (small, for the move list)
        num.setTextSize(cell * 0.17f);
        for (int s = 0; s < 64; s++) {
            if (((s >> 3) + (s & 7)) % 2 == 1)
                c.drawText(String.valueOf(Board.num(s)), sx(s) + cell * 0.05f, sy(s) + cell * 0.18f, num);
        }

        // pieces
        crown.setTextSize(cell * 0.42f);
        Paint.FontMetrics fm = crown.getFontMetrics();
        float yOff = -(fm.ascent + fm.descent) / 2f;
        for (int s = 0; s < 64; s++) {
            int p = board.sq[s];
            if (p == 0) continue;
            float cx = sx(s) + cell / 2, cy = sy(s) + cell / 2, r = cell * 0.40f;
            boolean black = p > 0;
            int base = black ? Color.rgb(35, 35, 35) : Color.rgb(200, 30, 30);
            int rim = black ? Color.rgb(90, 90, 90) : Color.rgb(250, 110, 100);
            fill.setColor(Color.argb(90, 0, 0, 0));
            c.drawCircle(cx + cell * 0.03f, cy + cell * 0.04f, r, fill); // shadow
            fill.setColor(base);
            c.drawCircle(cx, cy, r, fill);
            fill.setStyle(Paint.Style.STROKE);
            fill.setStrokeWidth(cell * 0.04f);
            fill.setColor(rim);
            c.drawCircle(cx, cy, r * 0.72f, fill);
            fill.setStyle(Paint.Style.FILL);
            if (Math.abs(p) == 2) {
                crown.setColor(Color.rgb(255, 200, 40));
                c.drawText("♛︎", cx, cy + yOff, crown);
            }
        }

        // destination dots for the selected piece
        for (Board.Move m : selMoves) {
            fill.setColor(Color.argb(150, 255, 255, 255));
            c.drawCircle(sx(m.to) + cell / 2, sy(m.to) + cell / 2, cell * 0.14f, fill);
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (e.getAction() != MotionEvent.ACTION_DOWN) return true;
        if (!inputEnabled || board == null || cell == 0) return true;
        int col = (int) (e.getX() / cell), row = (int) (e.getY() / cell);
        if (col < 0 || col > 7 || row < 0 || row > 7) return true;
        int s = squareAt(col, row);

        if (selected >= 0) {
            Board.Move chosen = null;
            for (Board.Move m : selMoves) {
                if (m.to == s && (chosen == null || m.caps.length > chosen.caps.length)) chosen = m;
            }
            if (chosen != null) {
                clearSelection();
                performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                if (listener != null) listener.onUserMove(chosen);
                return true;
            }
        }
        selMoves.clear();
        selected = -1;
        hint = null;
        for (Board.Move m : board.legalMoves()) if (m.from == s) selMoves.add(m);
        if (!selMoves.isEmpty()) selected = s;
        invalidate();
        return true;
    }
}
