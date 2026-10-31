package dev.smpeconomy.features.minigames.mega;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;

/**
 * Hard math tasks with integer results. Each task is built from its solution and computed with exact integer
 * arithmetic; the selftests check 10,000 samples per kind independently by re-parsing the displayed text.
 * <p>Guessing doesn't pay: each kind has a large answer range without clusters, and the four most common solutions
 * cover well under 2 % (selftest {@code mega-answer-space}). Hence divisor sum rather than divisor count (only about
 * 50 values, with 48/96/72/144 covering 32 %), modulo with primes 101-997 and an exponent coprime to p-1
 * (exponentiation is then a bijection, with no clustering at squares), and roots up to ±200.
 */
public final class MegaMath {
	public static final List<String> KINDS = List.of("quadratic", "linear_system", "modulo", "gcd", "lcm", "divisors", "series", "binom",
		"bases", "expression");
	private static final int[] PRIMES = primesBetween(101, 997);
	private static final String SUP = "⁰¹²³⁴⁵⁶⁷⁸⁹";
	static final String MINUS = "−";

	private MegaMath() {}

	public static String label(String kind) {
		return switch (kind) {
			case "quadratic" -> "Quadratic Equation";
			case "linear_system" -> "System of Equations";
			case "modulo" -> "Modular Power";
			case "gcd" -> "Greatest Common Divisor";
			case "lcm" -> "Least Common Multiple";
			case "divisors" -> "Sum of Divisors";
			case "series" -> "Series";
			case "binom" -> "Combinatorics";
			case "bases" -> "Number Bases";
			case "expression" -> "Expression";
			default -> kind;
		};
	}

	public static MegaQuestion create(String kind, Random r) {
		MegaQuestion.Builder b = MegaQuestion.builder(MegaChallenge.Type.MATH, kind, label(kind));
		switch (kind) {
			case "quadratic" -> quadratic(b, r);
			case "linear_system" -> linearSystem(b, r);
			case "modulo" -> modPow(b, r);
			case "gcd" -> gcd(b, r);
			case "lcm" -> lcm(b, r);
			case "divisors" -> divisors(b, r);
			case "series" -> series(b, r);
			case "binom" -> binom(b, r);
			case "bases" -> base(b, r);
			case "expression" -> expression(b, r);
			default -> throw new IllegalArgumentException("Unknown math task: " + kind + " (allowed: " + KINDS + ")");
		}
		return b.build();
	}

	private static void quadratic(MegaQuestion.Builder b, Random r) {
		int a = between(r, 1, 3);
		int r1, r2;
		do {
			r1 = between(r, -200, 200);
			r2 = between(r, -200, 200);
		} while (r1 == 0 || r2 == 0 || r1 == r2 || r1 == -r2);
		long A = a, B = -(long) a * (r1 + r2), C = (long) a * r1 * r2;
		boolean larger = r.nextBoolean();
		long answer = larger ? Math.max(r1, r2) : Math.min(r1, r2);
		String eq = poly(A, B, C) + " = 0";
		long disc = B * B - 4 * A * C;
		b.line("§fThe equation §e" + eq + " §fhas two integer solutions.");
		b.line("§fWhat is the §e§l" + (larger ? "LARGER" : "SMALLER") + "§f solution?");
		b.number(answer);
		b.explain("§7Discriminant: " + num(B) + "² " + MINUS + " 4·" + A + "·" + paren(C) + " = " + disc + " = " + (long) Math.abs((long) a * (r1 - r2)) + "²");
		b.explain("§7" + eq + "  ⇔  " + (a > 1 ? a + " · " : "") + "(x " + signed(-r1) + ") · (x " + signed(-r2) + ") = 0");
		b.explain("§7Solutions: x = " + num(Math.min(r1, r2)) + " and x = " + num(Math.max(r1, r2)) + " → " + (larger ? "larger" : "smaller")
			+ ": §a" + num(answer));
	}

	private static void linearSystem(MegaQuestion.Builder b, Random r) {
		int x, y, a, bb, c, d;
		do {
			x = nonZero(r, -60, 60);
			y = nonZero(r, -60, 60);
			a = nonZero(r, -9, 9);
			bb = nonZero(r, -9, 9);
			c = nonZero(r, -9, 9);
			d = nonZero(r, -9, 9);
		} while ((long) a * d - (long) bb * c == 0 || Math.abs(x) < 2 || Math.abs(y) < 2);
		long e = (long) a * x + (long) bb * y;
		long f = (long) c * x + (long) d * y;
		long det = (long) a * d - (long) bb * c;
		long answer = (long) x * y;
		b.line("§fSolve the system of equations:");
		b.line("§e   I:  " + linear(a, bb, e));
		b.line("§e  II:  " + linear(c, d, f));
		b.line("§fWhat is the product §e§lx · y§f?");
		b.number(answer);
		b.explain("§7Determinant D = " + num(a) + "·" + paren(d) + " " + MINUS + " " + paren(bb) + "·" + paren(c) + " = " + num(det));
		b.explain("§7x = (" + num(e) + "·" + paren(d) + " " + MINUS + " " + paren(bb) + "·" + paren(f) + ") / " + paren(det) + " = " + num(x)
			+ ",  y = (" + num(a) + "·" + paren(f) + " " + MINUS + " " + paren(e) + "·" + paren(c) + ") / " + paren(det) + " = " + num(y));
		b.explain("§7x · y = " + num(x) + " · " + paren(y) + " = §a" + num(answer));
	}

	private static void modPow(MegaQuestion.Builder b, Random r) {
		int m = PRIMES[r.nextInt(PRIMES.length)];
		int base;
		do {
			base = between(r, 100, 9999);
		} while (base % m == 0);
		int e;
		do {
			e = between(r, 1000, 99999);
		} while (gcd(e, m - 1) != 1); // coprime to p-1: x -> x^e is invertible, so solutions are uniformly distributed
		long answer = BigInteger.valueOf(base).modPow(BigInteger.valueOf(e), BigInteger.valueOf(m)).longValue();
		int rest = e % (m - 1);
		int reduced = base % m;
		b.line("§fWhat is the remainder of §e" + base + "^" + e + " §fdivided by §e" + m + "§f?");
		b.number(answer);
		b.explain("§7" + m + " is a prime and doesn't divide " + base + " → Fermat's little theorem: a^" + (m - 1) + " ≡ 1 (mod " + m + ")");
		b.explain("§7" + base + " ≡ " + reduced + " (mod " + m + "),  " + e + " = " + (e / (m - 1)) + " · " + (m - 1) + " + " + rest);
		b.explain("§7" + base + "^" + e + " ≡ " + reduced + "^" + rest + " ≡ §a" + answer + " §7(mod " + m + ")");
	}

	private static void gcd(MegaQuestion.Builder b, Random r) {
		int g, m1, m2, m3;
		do {
			g = between(r, 100, 999);
			m1 = between(r, 5, 60);
			m2 = between(r, 5, 60);
			m3 = between(r, 5, 60);
		} while (m1 == m2 || m1 == m3 || m2 == m3 || gcd(gcd(m1, m2), m3) != 1);
		long n1 = (long) g * m1, n2 = (long) g * m2, n3 = (long) g * m3;
		b.line("§fCalculate the greatest common divisor: §egcd(" + n1 + ", " + n2 + ", " + n3 + ")");
		b.number(g);
		b.explain("§7" + n1 + " = " + factorText(n1) + ",  " + n2 + " = " + factorText(n2) + ",  " + n3 + " = " + factorText(n3));
		b.explain("§7Common prime factors (lowest power): " + factorText(g) + " = §a" + g);
	}

	private static void lcm(MegaQuestion.Builder b, Random r) {
		long n1, n2, n3, l;
		do {
			n1 = between(r, 12, 99);
			n2 = between(r, 12, 99);
			n3 = between(r, 12, 99);
			l = lcm(lcm(n1, n2), n3);
		} while (n1 == n2 || n1 == n3 || n2 == n3 || l < 2000 || l > 2_000_000);
		b.line("§fCalculate the least common multiple: §elcm(" + n1 + ", " + n2 + ", " + n3 + ")");
		b.number(l);
		b.explain("§7" + n1 + " = " + factorText(n1) + ",  " + n2 + " = " + factorText(n2) + ",  " + n3 + " = " + factorText(n3));
		b.explain("§7Every prime factor with its highest power: " + factorText(l) + " = §a" + l);
	}

	/** n only has prime factors up to 13, so it can be factored by hand. */
	private static void divisors(MegaQuestion.Builder b, Random r) {
		int[] primes = {2, 3, 5, 7, 11, 13};
		int[] maxExp = {9, 5, 3, 2, 2, 1};
		long n;
		long d;
		long sigma;
		do {
			n = 1;
			d = 1;
			sigma = 1;
			for (int i = 0; i < primes.length; i++) {
				int e = r.nextInt(maxExp[i] + 1);
				long pk = 1;
				long sum = 1;
				for (int k = 0; k < e; k++) {
					pk *= primes[i];
					sum += pk;
				}
				n *= pk;
				d *= e + 1;
				sigma *= sum;
			}
		} while (n < 5000 || n > 9_999_999 || d < 24);
		b.line("§fWhat is the §e§lsum of all positive divisors §fof §e" + n + "§f? §7(1 and " + n + " itself count too)");
		b.line("§8Tip: " + n + " only has prime factors up to 13.");
		b.number(sigma);
		Map<Long, Integer> f = factor(n);
		StringBuilder prod = new StringBuilder();
		StringBuilder vals = new StringBuilder();
		for (Map.Entry<Long, Integer> e : f.entrySet()) {
			long p = e.getKey();
			int k = e.getValue();
			long sum = 0;
			long pk = 1;
			for (int i = 0; i <= k; i++) {
				sum += pk;
				pk *= p;
			}
			prod.append(prod.isEmpty() ? "" : " · ").append("(1").append(k >= 1 ? " + " + p : "").append(k == 2 ? " + " + p + "²" : k > 2 ? " + … + " + p + sup(k) : "")
				.append(")");
			vals.append(vals.isEmpty() ? "" : " · ").append(sum);
		}
		b.explain("§7" + n + " = " + factorText(n) + " → sum of divisors = product of (1 + p + … + p^k)");
		b.explain("§7" + prod + " = " + vals + " = §a" + sigma);
	}

	private static void series(MegaQuestion.Builder b, Random r) {
		switch (r.nextInt(4)) {
			case 0 -> {
				long a = between(r, 1, 40), d = between(r, 2, 17), n = between(r, 25, 150);
				long last = a + (n - 1) * d;
				long sum = n * (a + last) / 2;
				b.line("§fCalculate the sum: §e" + a + " + " + (a + d) + " + " + (a + 2 * d) + " + … + " + last);
				b.number(sum);
				b.explain("§7Arithmetic series with step " + d + ": (" + last + " " + MINUS + " " + a + ") / " + d + " + 1 = " + n + " terms");
				b.explain("§7Sum = " + n + " · (" + a + " + " + last + ") / 2 = §a" + sum);
			}
			case 1 -> {
				long n = between(r, 15, 200);
				long sum = n * (n + 1) * (2 * n + 1) / 6;
				b.line("§fCalculate the sum: §e1² + 2² + 3² + … + " + n + "²");
				b.number(sum);
				b.explain("§7Formula n·(n+1)·(2n+1) / 6 with n = " + n + ": " + n + "·" + (n + 1) + "·" + (2 * n + 1) + " / 6 = §a" + sum);
			}
			case 2 -> {
				long n = between(r, 10, 150);
				long t = n * (n + 1) / 2;
				long sum = t * t;
				b.line("§fCalculate the sum: §e1³ + 2³ + 3³ + … + " + n + "³");
				b.number(sum);
				b.explain("§7Formula (n·(n+1)/2)² with n = " + n + ": " + t + "² = §a" + sum);
			}
			default -> {
				long a, q, k, last;
				do {
					a = between(r, 2, 9);
					q = between(r, 2, 5);
					k = between(r, 8, 14);
					last = a * pow(q, (int) k - 1);
				} while (last < 5_000 || last > 50_000_000L);
				long sum = a * (pow(q, (int) k) - 1) / (q - 1);
				b.line("§fCalculate the sum: §e" + a + " + " + a * q + " + " + a * q * q + " + … + " + last);
				b.number(sum);
				b.explain("§7Geometric series with ratio " + q + ": " + last + " = " + a + "·" + q + "^" + (k - 1) + " → " + k + " terms");
				b.explain("§7Sum = " + a + " · (" + q + "^" + k + " " + MINUS + " 1) / (" + q + " " + MINUS + " 1) = §a" + sum);
			}
		}
	}

	private static void binom(MegaQuestion.Builder b, Random r) {
		int n = between(r, 20, 99);
		int k = between(r, 3, 8);
		long numerator = 1;
		for (int i = 0; i < k; i++) numerator *= n - i;
		long kf = 1;
		for (int i = 2; i <= k; i++) kf *= i;
		long value = numerator / kf;
		b.line("§fIn how many ways can you pick §e" + k + " §fof §e" + n + " §fplayers for a team? §7(order doesn't matter)");
		b.number(value);
		b.explain("§7C(" + n + ", " + k + ") = " + n + "·" + (n - 1) + "·…·" + (n - k + 1) + " / " + k + "! = " + numerator + " / " + kf + " = §a" + value);
	}

	private static void base(MegaQuestion.Builder b, Random r) {
		if (r.nextBoolean()) {
			int[] bases = {2, 3, 5, 7, 8, 16};
			int base = bases[r.nextInt(bases.length)];
			long v = switch (base) {
				case 2 -> between(r, 300, 4095);
				case 3 -> between(r, 300, 19682);
				case 5 -> between(r, 500, 15624);
				case 7 -> between(r, 500, 16806);
				case 8 -> between(r, 1000, 32767);
				default -> between(r, 4096, 65535);
			};
			String digits = Long.toString(v, base).toUpperCase(Locale.ROOT);
			b.line("§fConvert the number §e" + digits + " §ffrom §ebase " + base + " §fto decimal (base 10).");
			b.number(v);
			b.explain("§7" + digits + " = " + positional(digits, base) + " = §a" + v);
		} else {
			int[] bases = {2, 3, 5, 6, 7, 8, 9};
			int base = bases[r.nextInt(bases.length)];
			long v = switch (base) {
				case 2 -> between(r, 100, 1023);
				case 3 -> between(r, 100, 2186);
				default -> between(r, 200, 4000);
			};
			String digits = Long.toString(v, base);
			b.line("§fWrite the decimal number §e" + v + " §fin §ebase " + base + "§f.");
			b.number(digits);
			b.explain("§7" + v + " = " + positional(digits, base) + " → §a" + digits);
		}
	}

	interface Ex {
		long eval();

		int prec();

		String render();
	}

	record Num(long v) implements Ex {
		public long eval() {
			return v;
		}

		public int prec() {
			return 9;
		}

		public String render() {
			return v < 0 ? MINUS + (-v) : Long.toString(v);
		}
	}

	record Pow(Ex base, int exp) implements Ex {
		public long eval() {
			return pow(base.eval(), exp);
		}

		public int prec() {
			return 3;
		}

		public String render() {
			String inner = base instanceof Num n && n.v >= 0 ? base.render() : "(" + base.render() + ")";
			return inner + sup(exp);
		}
	}

	record Bin(char op, Ex l, Ex r) implements Ex {
		public long eval() {
			long a = l.eval(), b = r.eval();
			return switch (op) {
				case '+' -> Math.addExact(a, b);
				case '-' -> Math.subtractExact(a, b);
				case '*' -> Math.multiplyExact(a, b);
				default -> {
					if (b == 0 || a % b != 0) throw new ArithmeticException("division with remainder");
					yield a / b;
				}
			};
		}

		public int prec() {
			return op == '+' || op == '-' ? 1 : 2;
		}

		public String render() {
			String left = l.prec() < prec() ? "(" + l.render() + ")" : l.render();
			boolean wrapRight = r.prec() < prec() || r.prec() == prec() && (op == '-' || op == '/');
			String right = wrapRight ? "(" + r.render() + ")" : r.render();
			String sym = switch (op) {
				case '+' -> " + ";
				case '-' -> " " + MINUS + " ";
				case '*' -> " × ";
				default -> " ÷ ";
			};
			return left + sym + right;
		}
	}

	private static Ex n(long v) {
		return new Num(v);
	}

	private static Ex p(Ex base, int exp) {
		return new Pow(base, exp);
	}

	private static Ex op(char op, Ex l, Ex r) {
		return new Bin(op, l, r);
	}

	/** Division always without remainder, result magnitude at most 10M. */
	static Ex randomExpression(Random r) {
		for (int attempt = 0; attempt < 500; attempt++) {
			Ex e = switch (r.nextInt(5)) {
				case 0 -> op('-', op('*', op('-', p(n(between(r, 2, 9)), between(r, 2, 3)), p(n(between(r, 2, 9)), between(r, 2, 3))),
					op('+', n(between(r, 2, 20)), n(between(r, 2, 20)))), op('*', n(between(r, 2, 15)), n(between(r, 2, 15))));
				case 1 -> {
					int a = between(r, 3, 15), bb = between(r, 3, 15);
					int c = between(r, 1, a * bb - 2);
					long x = (long) a * bb - c;
					long dv = divisorOf(x * x, r, 2, 30);
					if (dv < 0) yield null;
					yield op('+', op('/', p(op('-', op('*', n(a), n(bb)), n(c)), 2), n(dv)), p(n(between(r, 2, 9)), 3));
				}
				case 2 -> op('+', op('-', p(n(between(r, 5, 15)), 3), p(op('+', n(between(r, 2, 15)), n(between(r, 2, 15))), 2)),
					op('*', n(between(r, 2, 12)), op('-', n(between(r, 1, 30)), n(between(r, 1, 30)))));
				case 3 -> {
					int a = between(r, 2, 20), bb = between(r, 2, 20);
					long s = (long) (a + bb) * (a + bb);
					long dv = divisorOf(s, r, 2, 25);
					if (dv < 0) yield null;
					yield op('+', op('-', op('/', p(op('+', n(a), n(bb)), 2), n(dv)), op('*', n(between(r, 2, 12)), n(between(r, 2, 12)))),
						p(n(between(r, 2, 6)), between(r, 2, 4)));
				}
				default -> {
					int a = between(r, 10, 30);
					yield op('-', p(op('+', op('*', op('-', n(a), n(between(r, 1, a - 1))), n(between(r, 2, 9))), n(between(r, 1, 20))), 2),
						op('*', n(between(r, 2, 9)), p(n(between(r, 2, 12)), 2)));
				}
			};
			if (e == null) continue;
			try {
				long v = e.eval();
				if (Math.abs(v) <= 10_000_000L) return e;
			} catch (ArithmeticException ignored) {
			}
		}
		throw new IllegalStateException("No valid expression found");
	}

	private static void expression(MegaQuestion.Builder b, Random r) {
		Ex e = randomExpression(r);
		long v = e.eval();
		b.line("§fCalculate: §e" + e.render());
		b.number(v);
		List<String> steps = new ArrayList<>();
		steps(e, steps);
		for (int i = 0; i < steps.size(); i += 4) {
			b.explain("§7" + String.join("  §8│  §7", steps.subList(i, Math.min(steps.size(), i + 4))));
		}
		b.explain("§7Result: §a" + num(v));
	}

	private static void steps(Ex e, List<String> out) {
		if (e instanceof Pow pw) {
			steps(pw.base(), out);
			out.add(paren(pw.base().eval()) + sup(pw.exp()) + " = " + num(pw.eval()));
		} else if (e instanceof Bin bn) {
			steps(bn.l(), out);
			steps(bn.r(), out);
			String sym = switch (bn.op()) {
				case '+' -> " + ";
				case '-' -> " " + MINUS + " ";
				case '*' -> " × ";
				default -> " ÷ ";
			};
			out.add(num(bn.l().eval()) + sym + paren(bn.r().eval()) + " = " + num(bn.eval()));
		}
	}

	private static int[] primesBetween(int from, int to) {
		List<Integer> out = new ArrayList<>();
		for (int p = Math.max(2, from); p <= to; p++) {
			boolean prime = true;
			for (int d = 2; d * d <= p; d++) {
				if (p % d == 0) {
					prime = false;
					break;
				}
			}
			if (prime) out.add(p);
		}
		return out.stream().mapToInt(Integer::intValue).toArray();
	}

	static int between(Random r, int min, int max) {
		return max <= min ? min : min + r.nextInt(max - min + 1);
	}

	private static int nonZero(Random r, int min, int max) {
		int v;
		do {
			v = between(r, min, max);
		} while (v == 0);
		return v;
	}

	private static long divisorOf(long x, Random r, int min, int max) {
		List<Long> ds = new ArrayList<>();
		for (long d = min; d <= max; d++) {
			if (x % d == 0) ds.add(d);
		}
		return ds.isEmpty() ? -1 : ds.get(r.nextInt(ds.size()));
	}

	static long gcd(long a, long b) {
		a = Math.abs(a);
		b = Math.abs(b);
		while (b != 0) {
			long t = a % b;
			a = b;
			b = t;
		}
		return a;
	}

	static long lcm(long a, long b) {
		return a / gcd(a, b) * b;
	}

	static long pow(long base, int exp) {
		long v = 1;
		for (int i = 0; i < exp; i++) v = Math.multiplyExact(v, base);
		return v;
	}

	static Map<Long, Integer> factor(long n) {
		Map<Long, Integer> f = new LinkedHashMap<>();
		long m = n;
		for (long p = 2; p * p <= m; p++) {
			while (m % p == 0) {
				f.merge(p, 1, Integer::sum);
				m /= p;
			}
		}
		if (m > 1) f.merge(m, 1, Integer::sum);
		return f;
	}

	static String factorText(long n) {
		if (n == 1) return "1";
		StringBuilder sb = new StringBuilder();
		for (Map.Entry<Long, Integer> e : factor(n).entrySet()) {
			if (!sb.isEmpty()) sb.append(" · ");
			sb.append(e.getKey());
			if (e.getValue() > 1) sb.append(sup(e.getValue()));
		}
		return sb.toString();
	}

	static String sup(int n) {
		StringBuilder sb = new StringBuilder();
		for (char c : Integer.toString(n).toCharArray()) sb.append(SUP.charAt(c - '0'));
		return sb.toString();
	}

	private static String positional(String digits, int base) {
		StringBuilder sb = new StringBuilder();
		int len = digits.length();
		for (int i = 0; i < len; i++) {
			int dv = Character.digit(digits.charAt(i), base);
			if (dv == 0) continue;
			int exp = len - 1 - i;
			if (!sb.isEmpty()) sb.append(" + ");
			sb.append(dv);
			if (exp > 0) sb.append("·").append(base).append(exp > 1 ? sup(exp) : "");
		}
		return sb.isEmpty() ? "0" : sb.toString();
	}

	private static String poly(long a, long b, long c) {
		StringBuilder sb = new StringBuilder();
		sb.append(a == 1 ? "" : a == -1 ? MINUS : num(a)).append("x²");
		if (b != 0) sb.append(b < 0 ? " " + MINUS + " " : " + ").append(Math.abs(b) == 1 ? "" : Long.toString(Math.abs(b))).append("x");
		if (c != 0) sb.append(c < 0 ? " " + MINUS + " " : " + ").append(Math.abs(c));
		return sb.toString();
	}

	private static String linear(long a, long b, long rhs) {
		StringBuilder sb = new StringBuilder();
		sb.append(a == 1 ? "" : a == -1 ? MINUS : num(a)).append("x");
		sb.append(b < 0 ? " " + MINUS + " " : " + ").append(Math.abs(b) == 1 ? "" : Long.toString(Math.abs(b))).append("y");
		sb.append(" = ").append(num(rhs));
		return sb.toString();
	}

	static String num(long v) {
		return v < 0 ? MINUS + (-v) : Long.toString(v);
	}

	static String paren(long v) {
		return v < 0 ? "(" + num(v) + ")" : num(v);
	}

	private static String signed(long v) {
		return v < 0 ? MINUS + " " + (-v) : "+ " + v;
	}
}
