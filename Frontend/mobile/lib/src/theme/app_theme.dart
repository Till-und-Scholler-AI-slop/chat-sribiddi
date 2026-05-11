import 'package:flutter/material.dart';

class AppTheme {
  static const _accent = Color(0xFF2E8B57);
  static const _paper = Color(0xFFF3EEE5);
  static const _paperElevated = Color(0xFFEDE5D8);
  static const _ink = Color(0xFF312A24);
  static const _darkPaper = Color(0xFF23201D);
  static const _darkElevated = Color(0xFF2C2925);
  static const _darkInk = Color(0xFFF2EEE8);

  static ThemeData light() {
    return _build(
      brightness: Brightness.light,
      paper: _paper,
      elevated: _paperElevated,
      ink: _ink,
      muted: const Color(0xFF756B60),
      hairline: const Color(0xFFD8CDBC),
    );
  }

  static ThemeData dark() {
    return _build(
      brightness: Brightness.dark,
      paper: _darkPaper,
      elevated: _darkElevated,
      ink: _darkInk,
      muted: const Color(0xFFB9B0A6),
      hairline: const Color(0xFF48423B),
    );
  }

  static ThemeData _build({
    required Brightness brightness,
    required Color paper,
    required Color elevated,
    required Color ink,
    required Color muted,
    required Color hairline,
  }) {
    final scheme = ColorScheme.fromSeed(
      seedColor: _accent,
      brightness: brightness,
      primary: _accent,
      surface: paper,
      surfaceContainerHighest: elevated,
      onSurface: ink,
    );

    final base = ThemeData(
      useMaterial3: true,
      brightness: brightness,
      colorScheme: scheme,
      scaffoldBackgroundColor: paper,
      fontFamily: 'Roboto',
    );

    return base.copyWith(
      appBarTheme: AppBarTheme(
        backgroundColor: paper,
        foregroundColor: ink,
        elevation: 0,
        centerTitle: false,
        titleTextStyle: TextStyle(
          color: ink,
          fontSize: 18,
          fontWeight: FontWeight.w700,
        ),
      ),
      cardTheme: CardThemeData(
        color: elevated,
        elevation: 0,
        margin: EdgeInsets.zero,
        shape: RoundedRectangleBorder(
          borderRadius: BorderRadius.circular(10),
          side: BorderSide(color: hairline),
        ),
      ),
      navigationBarTheme: NavigationBarThemeData(
        backgroundColor: elevated,
        indicatorColor: _accent.withValues(alpha: 0.16),
        labelBehavior: NavigationDestinationLabelBehavior.onlyShowSelected,
      ),
      navigationRailTheme: NavigationRailThemeData(
        backgroundColor: elevated,
        indicatorColor: _accent.withValues(alpha: 0.16),
        selectedIconTheme: const IconThemeData(color: _accent),
        selectedLabelTextStyle: const TextStyle(
          color: _accent,
          fontWeight: FontWeight.w700,
        ),
        unselectedIconTheme: IconThemeData(color: muted),
        unselectedLabelTextStyle: TextStyle(color: muted),
      ),
      chipTheme: base.chipTheme.copyWith(
        backgroundColor: elevated,
        selectedColor: _accent.withValues(alpha: 0.16),
        side: BorderSide(color: hairline),
        labelStyle: TextStyle(color: ink, fontWeight: FontWeight.w600),
      ),
      inputDecorationTheme: InputDecorationTheme(
        filled: true,
        fillColor: brightness == Brightness.light
            ? const Color(0xFFF8F4EE)
            : const Color(0xFF302C28),
        border: OutlineInputBorder(
          borderRadius: BorderRadius.circular(10),
          borderSide: BorderSide(color: hairline),
        ),
        enabledBorder: OutlineInputBorder(
          borderRadius: BorderRadius.circular(10),
          borderSide: BorderSide(color: hairline),
        ),
        focusedBorder: OutlineInputBorder(
          borderRadius: BorderRadius.circular(10),
          borderSide: const BorderSide(color: _accent, width: 1.5),
        ),
      ),
      dividerTheme: DividerThemeData(color: hairline, space: 1),
      textTheme: base.textTheme.apply(
        bodyColor: ink,
        displayColor: ink,
      ).copyWith(
        headlineSmall: TextStyle(
          color: ink,
          fontSize: 24,
          fontWeight: FontWeight.w700,
          letterSpacing: 0,
        ),
        titleLarge: TextStyle(
          color: ink,
          fontSize: 20,
          fontWeight: FontWeight.w700,
          letterSpacing: 0,
        ),
        titleMedium: TextStyle(
          color: ink,
          fontSize: 16,
          fontWeight: FontWeight.w700,
          letterSpacing: 0,
        ),
        bodySmall: TextStyle(color: muted),
      ),
      extensions: <ThemeExtension<dynamic>>[
        ChatPalette(
          paper: paper,
          elevated: elevated,
          ink: ink,
          muted: muted,
          hairline: hairline,
          accent: _accent,
          accentSoft: _accent.withValues(alpha: 0.12),
          userBubble: brightness == Brightness.light
              ? const Color(0xFFE2F2E8)
              : const Color(0xFF254533),
          assistantBubble: brightness == Brightness.light
              ? const Color(0xFFF8F4EE)
              : const Color(0xFF302C28),
          dangerSoft: brightness == Brightness.light
              ? const Color(0xFFF8E4DE)
              : const Color(0xFF472B25),
        ),
      ],
    );
  }
}

@immutable
class ChatPalette extends ThemeExtension<ChatPalette> {
  const ChatPalette({
    required this.paper,
    required this.elevated,
    required this.ink,
    required this.muted,
    required this.hairline,
    required this.accent,
    required this.accentSoft,
    required this.userBubble,
    required this.assistantBubble,
    required this.dangerSoft,
  });

  final Color paper;
  final Color elevated;
  final Color ink;
  final Color muted;
  final Color hairline;
  final Color accent;
  final Color accentSoft;
  final Color userBubble;
  final Color assistantBubble;
  final Color dangerSoft;

  static ChatPalette of(BuildContext context) {
    return Theme.of(context).extension<ChatPalette>()!;
  }

  @override
  ChatPalette copyWith({
    Color? paper,
    Color? elevated,
    Color? ink,
    Color? muted,
    Color? hairline,
    Color? accent,
    Color? accentSoft,
    Color? userBubble,
    Color? assistantBubble,
    Color? dangerSoft,
  }) {
    return ChatPalette(
      paper: paper ?? this.paper,
      elevated: elevated ?? this.elevated,
      ink: ink ?? this.ink,
      muted: muted ?? this.muted,
      hairline: hairline ?? this.hairline,
      accent: accent ?? this.accent,
      accentSoft: accentSoft ?? this.accentSoft,
      userBubble: userBubble ?? this.userBubble,
      assistantBubble: assistantBubble ?? this.assistantBubble,
      dangerSoft: dangerSoft ?? this.dangerSoft,
    );
  }

  @override
  ChatPalette lerp(ThemeExtension<ChatPalette>? other, double t) {
    if (other is! ChatPalette) return this;
    return ChatPalette(
      paper: Color.lerp(paper, other.paper, t)!,
      elevated: Color.lerp(elevated, other.elevated, t)!,
      ink: Color.lerp(ink, other.ink, t)!,
      muted: Color.lerp(muted, other.muted, t)!,
      hairline: Color.lerp(hairline, other.hairline, t)!,
      accent: Color.lerp(accent, other.accent, t)!,
      accentSoft: Color.lerp(accentSoft, other.accentSoft, t)!,
      userBubble: Color.lerp(userBubble, other.userBubble, t)!,
      assistantBubble: Color.lerp(assistantBubble, other.assistantBubble, t)!,
      dangerSoft: Color.lerp(dangerSoft, other.dangerSoft, t)!,
    );
  }
}
