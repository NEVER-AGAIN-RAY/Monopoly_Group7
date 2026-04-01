import 'package:flutter/material.dart';

import 'core/network/ws_client.dart';
import 'feature/debug/debug_console_page.dart';
import 'feature/game/game_page.dart';
import 'feature/game/game_view_model.dart';
import 'feature/lobby/lobby_page.dart';
import 'feature/lobby/lobby_view_model.dart';

/// 应用根组件，承载最小导航骨架。
class MonopolyApp extends StatelessWidget {
  const MonopolyApp({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'Monopoly Flutter',
      theme: ThemeData(useMaterial3: true),
      home: const AppScaffold(),
    );
  }
}

/// 首页壳层，负责三个 feature 页面切换。
class AppScaffold extends StatefulWidget {
  const AppScaffold({super.key});

  @override
  State<AppScaffold> createState() => _AppScaffoldState();
}

class _AppScaffoldState extends State<AppScaffold> {
  int _selectedIndex = 0;
  late final WsClient _wsClient;
  late final LobbyViewModel _lobbyViewModel;
  late final GameViewModel _gameViewModel;

  @override
  void initState() {
    super.initState();
    _wsClient = WsClient();
    _lobbyViewModel = LobbyViewModel(wsClient: _wsClient);
    _gameViewModel = GameViewModel(wsClient: _wsClient);
  }

  @override
  void dispose() {
    _gameViewModel.dispose();
    _wsClient.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('Monopoly Frontend Skeleton')),
      body: _buildPage(),
      bottomNavigationBar: NavigationBar(
        selectedIndex: _selectedIndex,
        onDestinationSelected: (int index) {
          setState(() {
            _selectedIndex = index;
          });
        },
        destinations: const <NavigationDestination>[
          NavigationDestination(icon: Icon(Icons.groups), label: 'Lobby'),
          NavigationDestination(icon: Icon(Icons.casino), label: 'Game'),
          NavigationDestination(icon: Icon(Icons.bug_report), label: 'Debug'),
        ],
      ),
    );
  }

  Widget _buildPage() {
    switch (_selectedIndex) {
      case 0:
        return LobbyPage(viewModel: _lobbyViewModel);
      case 1:
        return GamePage(viewModel: _gameViewModel);
      case 2:
        return DebugConsolePage(wsClient: _wsClient);
      default:
        return const SizedBox.shrink();
    }
  }
}
