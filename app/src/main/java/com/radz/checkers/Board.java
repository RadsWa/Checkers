package com.radz.checkers;

import java.util.ArrayList;
import java.util.List;

/**
 * English draughts (checkers) board. Squares 0..63, index = row * 8 + col, row 0 at the top.
 * Only dark squares ((row + col) odd) are used. Black starts on rows 0-2 and moves first (downwards);
 * Red starts on rows 5-7 and moves upwards.
 * Cells: 1 black man, 2 black king, -1 red man, -2 red king, 0 empty.
 */
public final class Board {
    public static final int BLACK = 1, RED = -1;

    public final int[] sq = new int[64];
    public int side = BLACK;
    public int quiet = 0; // plies since the last capture or man move (draw at 80 = 40 moves each)

    /** A complete move, including every jump of a multi-capture. */
    public static final class Move {
        public final int from, to;
        public final int[] path;     // squares landed on, in order (last == to)
        public final int[] caps;     // squares of captured pieces
        Move(int from, int[] path, int[] caps) {
            this.from = from; this.path = path; this.caps = caps; this.to = path[path.length - 1];
        }
        public boolean isCapture() { return caps.length > 0; }
    }

    // undo stack
    private final List<Move> hMove = new ArrayList<>();
    private final List<int[]> hCaps = new ArrayList<>();
    private final List<Integer> hPiece = new ArrayList<>();
    private final List<Integer> hQuiet = new ArrayList<>();

    private static final int[][] DIRS = {{-1, -1}, {-1, 1}, {1, -1}, {1, 1}};

    public Board() { reset(); }

    public void reset() {
        for (int i = 0; i < 64; i++) sq[i] = 0;
        for (int r = 0; r < 8; r++)
            for (int c = 0; c < 8; c++)
                if ((r + c) % 2 == 1) {
                    if (r < 3) sq[r * 8 + c] = 1;
                    else if (r > 4) sq[r * 8 + c] = -1;
                }
        side = BLACK;
        quiet = 0;
        hMove.clear(); hCaps.clear(); hPiece.clear(); hQuiet.clear();
    }

    public int ply() { return hMove.size(); }
    public Move moveAt(int i) { return hMove.get(i); }
    public Move lastMove() { return hMove.isEmpty() ? null : hMove.get(hMove.size() - 1); }

    /** Standard 1..32 square number (Black's back row = 1..4). */
    public static int num(int s) { return (s >> 3) * 4 + (s & 7) / 2 + 1; }

    public static String notation(Move m) {
        StringBuilder b = new StringBuilder().append(num(m.from));
        for (int p : m.path) b.append(m.isCapture() ? 'x' : '-').append(num(p));
        return b.toString();
    }

    private static boolean mine(int p, int who) { return p != 0 && (p > 0) == (who == BLACK); }

    /** All legal moves for the side to move. Captures are compulsory. */
    public List<Move> legalMoves() {
        List<Move> caps = new ArrayList<>();
        for (int s = 0; s < 64; s++) {
            if (!mine(sq[s], side)) continue;
            int p = sq[s];
            sq[s] = 0; // lift the piece while it jumps
            jumps(s, s, p, new ArrayList<>(), new ArrayList<>(), caps);
            sq[s] = p;
        }
        if (!caps.isEmpty()) return caps;
        List<Move> moves = new ArrayList<>();
        for (int s = 0; s < 64; s++) {
            int p = sq[s];
            if (!mine(p, side)) continue;
            int r = s >> 3, c = s & 7;
            for (int[] d : DIRS) {
                if (Math.abs(p) == 1 && d[0] != forward(p)) continue;
                int rr = r + d[0], cc = c + d[1];
                if (rr < 0 || rr > 7 || cc < 0 || cc > 7) continue;
                int t = rr * 8 + cc;
                if (sq[t] == 0) moves.add(new Move(s, new int[]{t}, new int[0]));
            }
        }
        return moves;
    }

    private static int forward(int p) { return p > 0 ? 1 : -1; }

    private void jumps(int from, int at, int p, List<Integer> path, List<Integer> capped, List<Move> out) {
        int r = at >> 3, c = at & 7;
        boolean any = false;
        boolean man = Math.abs(p) == 1;
        for (int[] d : DIRS) {
            if (man && d[0] != forward(p)) continue;
            int mr = r + d[0], mc = c + d[1], lr = r + 2 * d[0], lc = c + 2 * d[1];
            if (lr < 0 || lr > 7 || lc < 0 || lc > 7) continue;
            int mid = mr * 8 + mc, land = lr * 8 + lc;
            if (!mine(sq[mid], -side) || capped.contains(mid) || sq[land] != 0) continue;
            any = true;
            path.add(land); capped.add(mid);
            boolean crowns = man && (lr == (p > 0 ? 7 : 0));
            if (crowns) emit(from, path, capped, out); // crowning ends the move
            else jumps(from, land, p, path, capped, out);
            path.remove(path.size() - 1); capped.remove(capped.size() - 1);
        }
        if (!any && !path.isEmpty()) emit(from, path, capped, out);
    }

    private static void emit(int from, List<Integer> path, List<Integer> capped, List<Move> out) {
        int[] pa = new int[path.size()], ca = new int[capped.size()];
        for (int i = 0; i < pa.length; i++) pa[i] = path.get(i);
        for (int i = 0; i < ca.length; i++) ca[i] = capped.get(i);
        out.add(new Move(from, pa, ca));
    }

    public void make(Move m) {
        int p = sq[m.from];
        int[] capVals = new int[m.caps.length];
        for (int i = 0; i < m.caps.length; i++) { capVals[i] = sq[m.caps[i]]; sq[m.caps[i]] = 0; }
        sq[m.from] = 0;
        int placed = p;
        int tr = m.to >> 3;
        if (p == 1 && tr == 7) placed = 2;
        if (p == -1 && tr == 0) placed = -2;
        sq[m.to] = placed;
        hMove.add(m); hCaps.add(capVals); hPiece.add(p); hQuiet.add(quiet);
        quiet = (m.isCapture() || Math.abs(p) == 1) ? 0 : quiet + 1;
        side = -side;
    }

    public void unmake() {
        int i = hMove.size() - 1;
        Move m = hMove.remove(i);
        int[] capVals = hCaps.remove(i);
        int p = hPiece.remove(i);
        quiet = hQuiet.remove(i);
        sq[m.to] = 0;
        sq[m.from] = p;
        for (int k = 0; k < m.caps.length; k++) sq[m.caps[k]] = capVals[k];
        side = -side;
    }

    public int count(int who) {
        int n = 0;
        for (int p : sq) if (mine(p, who)) n++;
        return n;
    }

    public long perft(int depth) {
        if (depth == 0) return 1;
        long n = 0;
        for (Move m : legalMoves()) { make(m); n += perft(depth - 1); unmake(); }
        return n;
    }
}
