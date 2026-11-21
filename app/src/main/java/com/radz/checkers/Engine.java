package com.radz.checkers;

import java.util.List;
import java.util.Random;

/** Alpha-beta checkers AI with capture extension and iterative deepening. */
public final class Engine {
    static final int INF = 1_000_000, WIN = 100_000;
    public static final String[] LEVEL_NAMES = {"Beginner", "Easy", "Medium", "Hard", "Expert"};

    private final Random rnd = new Random();
    private Board b;
    private long deadline;
    private boolean stopped;
    private long nodes;
    private volatile boolean abort;

    public void abort() { abort = true; }

    /** Evaluation from the side-to-move's perspective. */
    int evaluate() {
        int score = 0, bMat = 0, rMat = 0, pieces = 0;
        for (int s = 0; s < 64; s++) {
            int p = b.sq[s];
            if (p == 0) continue;
            pieces++;
            int r = s >> 3, c = s & 7;
            int v;
            if (Math.abs(p) == 1) {
                int adv = p > 0 ? r : 7 - r;           // rows advanced
                v = 100 + adv * 4;
                boolean backRow = (p > 0 && r == 0) || (p < 0 && r == 7);
                if (backRow) v += 8;                   // guards against enemy kings
            } else {
                v = 170;
                int cd = Math.max(3 - r, r - 4) + Math.max(3 - c, c - 4);
                v += (6 - cd) * 3;                     // kings like the centre
            }
            if (c == 0 || c == 7) v -= 4;              // edge pieces are less active
            if (p > 0) { score += v; bMat += Math.abs(p) == 1 ? 100 : 170; }
            else { score -= v; rMat += Math.abs(p) == 1 ? 100 : 170; }
        }
        // When ahead, trading down is good
        int total = bMat + rMat;
        if (total > 0 && bMat != rMat) score += (bMat - rMat) * 400 / total / 4;
        // In the endgame, the side ahead should chase: reward kings near enemy pieces
        if (pieces <= 8 && bMat != rMat) {
            int lead = bMat > rMat ? Board.BLACK : Board.RED;
            int dist = 0;
            for (int s = 0; s < 64; s++) {
                int p = b.sq[s];
                if (p == 2 * lead) {
                    for (int t = 0; t < 64; t++) {
                        int q = b.sq[t];
                        if (q != 0 && (q > 0) != (lead > 0))
                            dist += Math.max(Math.abs((s >> 3) - (t >> 3)), Math.abs((s & 7) - (t & 7)));
                    }
                }
            }
            score -= lead * dist * 2;
        }
        return score * b.side;
    }

    private boolean timeUp() {
        if ((++nodes & 511) == 0 && (System.currentTimeMillis() > deadline || abort)) stopped = true;
        return stopped;
    }

    private int search(int depth, int alpha, int beta, int ply) {
        if (timeUp()) return 0;
        if (b.quiet >= 80) return 0;
        List<Board.Move> moves = b.legalMoves();
        if (moves.isEmpty()) return -WIN + ply;
        boolean forced = moves.get(0).isCapture();
        if (depth <= 0 && !forced) return evaluate();
        if (ply > 60) return evaluate();
        // captures first, bigger captures first
        moves.sort((x, y) -> y.caps.length - x.caps.length);
        int best = -INF;
        for (Board.Move m : moves) {
            b.make(m);
            // single forced reply does not use up depth
            int sc = -search(moves.size() == 1 ? depth : depth - 1, -beta, -alpha, ply + 1);
            b.unmake();
            if (stopped) return 0;
            if (sc > best) best = sc;
            if (sc > alpha) alpha = sc;
            if (alpha >= beta) break;
        }
        return best;
    }

    /** level 0..4; returns null if no legal moves. */
    public Board.Move findMove(Board board, int level) {
        b = new Board();
        copy(board, b);
        abort = false;
        stopped = false;
        nodes = 0;
        List<Board.Move> root = b.legalMoves();
        if (root.isEmpty()) return null;
        if (root.size() == 1) return root.get(0);

        int[] depths = {2, 3, 5, 9, 40};
        long[] times = {300, 600, 1200, 2500, 5000};
        int[] noise = {150, 60, 15, 0, 0};
        deadline = System.currentTimeMillis() + times[level];
        java.util.Collections.shuffle(root, rnd);

        if (noise[level] > 0) {
            Board.Move best = root.get(0);
            int bestScore = -INF;
            for (Board.Move m : root) {
                b.make(m);
                int sc = -search(depths[level] - 1, -INF, INF, 1);
                b.unmake();
                if (stopped) break;
                sc += rnd.nextInt(noise[level] * 2 + 1) - noise[level];
                if (sc > bestScore) { bestScore = sc; best = m; }
            }
            return best;
        }

        Board.Move bestMove = root.get(0);
        for (int depth = 1; depth <= depths[level]; depth++) {
            // previous best first
            root.remove(bestMove);
            root.add(0, bestMove);
            int alpha = -INF, iterScore = -INF;
            Board.Move iterBest = null;
            for (Board.Move m : root) {
                b.make(m);
                int sc = -search(depth - 1, -INF, -alpha, 1);
                b.unmake();
                if (stopped) break;
                if (sc > iterScore) { iterScore = sc; iterBest = m; }
                if (sc > alpha) alpha = sc;
            }
            if (iterBest != null) bestMove = iterBest;
            if (stopped) break;
            if (iterScore > WIN - 200) break;
            if (System.currentTimeMillis() > deadline - times[level] / 2) break;
        }
        return bestMove;
    }

    /** Copies a board including history by replaying its moves. */
    static void copy(Board src, Board dst) {
        dst.reset();
        for (int i = 0; i < src.ply(); i++) {
            Board.Move m = src.moveAt(i);
            for (Board.Move lm : dst.legalMoves())
                if (lm.from == m.from && java.util.Arrays.equals(lm.path, m.path)) { dst.make(lm); break; }
        }
    }
}
