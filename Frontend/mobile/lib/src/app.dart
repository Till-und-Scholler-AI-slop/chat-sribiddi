import 'package:flutter/material.dart';
import 'package:flutter_localizations/flutter_localizations.dart';

import 'app_state.dart';
import 'i18n/app_strings.dart';
import 'theme/app_theme.dart';
import 'widgets/app_shell.dart';

class ChatSribiddiMobileApp extends StatefulWidget {
  const ChatSribiddiMobileApp({super.key});

  @override
  State<ChatSribiddiMobileApp> createState() => _ChatSribiddiMobileAppState();
}

class _ChatSribiddiMobileAppState extends State<ChatSribiddiMobileApp> {
  late final AppController _controller;

  @override
  void initState() {
    super.initState();
    _controller = AppController();
  }

  @override
  void dispose() {
    _controller.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return AnimatedBuilder(
      animation: _controller,
      builder: (context, _) {
        final strings = AppStrings(_controller.locale);
        return AppScope(
          controller: _controller,
          child: MaterialApp(
            debugShowCheckedModeBanner: false,
            title: strings.t('common.appName'),
            locale: _controller.locale,
            supportedLocales: const [
              Locale('de'),
              Locale('en'),
            ],
            localizationsDelegates: GlobalMaterialLocalizations.delegates,
            themeMode: _controller.themeMode,
            theme: AppTheme.light(),
            darkTheme: AppTheme.dark(),
            home: const AppShell(),
          ),
        );
      },
    );
  }
}
