import 'package:flutter/material.dart';

import 'lobby_view_model.dart';

/// 大厅页，提供基础连接与会话参数输入。
class LobbyPage extends StatefulWidget {
  final LobbyViewModel viewModel;

  const LobbyPage({super.key, required this.viewModel});

  @override
  State<LobbyPage> createState() => _LobbyPageState();
}

class _LobbyPageState extends State<LobbyPage> {
  late final TextEditingController _urlCtrl;
  late final TextEditingController _playerIdCtrl;
  late final TextEditingController _sessionIdCtrl;
  late final TextEditingController _playerCountCtrl;
  late final TextEditingController _gameModeCtrl;

  @override
  void initState() {
    super.initState();
    _urlCtrl = TextEditingController(text: widget.viewModel.wsUrl);
    _playerIdCtrl = TextEditingController(text: widget.viewModel.playerId);
    _sessionIdCtrl = TextEditingController(text: widget.viewModel.sessionId);
    _playerCountCtrl = TextEditingController(
      text: widget.viewModel.playerCount.toString(),
    );
    _gameModeCtrl = TextEditingController(text: widget.viewModel.gameMode);
  }

  @override
  void dispose() {
    _urlCtrl.dispose();
    _playerIdCtrl.dispose();
    _sessionIdCtrl.dispose();
    _playerCountCtrl.dispose();
    _gameModeCtrl.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return SingleChildScrollView(
      padding: const EdgeInsets.all(12),
      child: Column(
        children: <Widget>[
          TextField(
            controller: _urlCtrl,
            decoration: const InputDecoration(labelText: 'WebSocket URL'),
          ),
          const SizedBox(height: 8),
          TextField(
            controller: _playerIdCtrl,
            decoration: const InputDecoration(labelText: 'Player ID'),
          ),
          const SizedBox(height: 8),
          TextField(
            controller: _sessionIdCtrl,
            decoration: const InputDecoration(labelText: 'Session ID'),
          ),
          const SizedBox(height: 8),
          TextField(
            controller: _playerCountCtrl,
            keyboardType: TextInputType.number,
            decoration: const InputDecoration(labelText: 'Player Count'),
          ),
          const SizedBox(height: 8),
          TextField(
            controller: _gameModeCtrl,
            decoration: const InputDecoration(labelText: 'Game Mode'),
          ),
          const SizedBox(height: 8),
          SwitchListTile(
            value: widget.viewModel.randomizeFirstPlayer,
            title: const Text('Randomize First Player'),
            onChanged: (bool value) {
              setState(() {
                widget.viewModel.randomizeFirstPlayer = value;
              });
            },
          ),
          const SizedBox(height: 8),
          Wrap(
            spacing: 8,
            runSpacing: 8,
            children: <Widget>[
              FilledButton(
                onPressed: () async {
                  _syncInputsToViewModel();
                  await widget.viewModel.connect();
                  if (context.mounted) {
                    ScaffoldMessenger.of(context).showSnackBar(
                      const SnackBar(content: Text('已发起连接')),
                    );
                  }
                },
                child: const Text('Connect'),
              ),
              OutlinedButton(
                onPressed: () {
                  _syncInputsToViewModel();
                  widget.viewModel.sendAuth();
                },
                child: const Text('AUTH'),
              ),
              OutlinedButton(
                onPressed: () {
                  _syncInputsToViewModel();
                  widget.viewModel.sendStartSession();
                },
                child: const Text('START_SESSION'),
              ),
            ],
          ),
        ],
      ),
    );
  }

  void _syncInputsToViewModel() {
    widget.viewModel.wsUrl = _urlCtrl.text;
    widget.viewModel.playerId = _playerIdCtrl.text;
    widget.viewModel.sessionId = _sessionIdCtrl.text;
    widget.viewModel.playerCount = int.tryParse(_playerCountCtrl.text) ?? 2;
    widget.viewModel.gameMode = _gameModeCtrl.text;
  }
}
