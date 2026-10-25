import 'package:flutter/material.dart';

import '../data/demo_data.dart';
import '../models/call_entry.dart';
import '../models/chat.dart';
import '../models/status_item.dart';
import '../theme/theme_controller.dart';
import 'calls_tab.dart';
import 'chat_screen.dart';
import 'chats_tab.dart';
import 'settings_screen.dart';
import 'status_tab.dart';

/// الشاشة الرئيسية بثلاثة تبويبات: المحادثات، الحالة، المكالمات.
class HomeScreen extends StatefulWidget {
  const HomeScreen({super.key, required this.themeController});

  final ThemeController themeController;

  @override
  State<HomeScreen> createState() => _HomeScreenState();
}

class _HomeScreenState extends State<HomeScreen>
    with SingleTickerProviderStateMixin {
  late final TabController _tabController;
  late final List<Chat> _chats;
  late final List<StatusItem> _statuses;
  late final List<CallEntry> _calls;

  @override
  void initState() {
    super.initState();
    _tabController = TabController(length: 3, vsync: this);
    _chats = buildDemoChats();
    _statuses = buildDemoStatuses();
    _calls = buildDemoCalls();
  }

  @override
  void dispose() {
    _tabController.dispose();
    super.dispose();
  }

  void _showDemoMessage(String text) {
    ScaffoldMessenger.of(context).showSnackBar(
      SnackBar(content: Text(text)),
    );
  }

  Future<void> _openChat(Chat chat) async {
    chat.unreadCount = 0;
    await Navigator.of(context).push(
      MaterialPageRoute<void>(
        builder: (BuildContext context) => ChatScreen(chat: chat),
      ),
    );
    if (mounted) {
      setState(() {});
    }
  }

  void _openSettings() {
    Navigator.of(context).push(
      MaterialPageRoute<void>(
        builder: (BuildContext context) =>
            SettingsScreen(themeController: widget.themeController),
      ),
    );
  }

  void _showAbout() {
    showAboutDialog(
      context: context,
      applicationName: 'Conanty',
      applicationVersion: '1.0.0',
      applicationLegalese: 'نسخة تجريبية — واجهة فقط ببيانات محلية.',
      children: const <Widget>[
        SizedBox(height: 12),
        Text(
          'واجهة محادثات مستوحاة من واتساب، مبنية بـ Flutter. '
          'مفيش سيرفر ولا حسابات: كل البيانات تجريبية ومحفوظة في الذاكرة.',
        ),
      ],
    );
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: const Text('Conanty'),
        actions: <Widget>[
          IconButton(
            icon: const Icon(Icons.camera_alt_outlined),
            tooltip: 'الكاميرا',
            onPressed: () => _showDemoMessage('الكاميرا — تجريبي'),
          ),
          PopupMenuButton<String>(
            tooltip: 'المزيد',
            onSelected: (String value) {
              if (value == 'settings') {
                _openSettings();
              } else if (value == 'about') {
                _showAbout();
              }
            },
            itemBuilder: (BuildContext context) =>
                const <PopupMenuEntry<String>>[
              PopupMenuItem<String>(
                value: 'settings',
                child: Text('الإعدادات'),
              ),
              PopupMenuItem<String>(
                value: 'about',
                child: Text('حول التطبيق'),
              ),
            ],
          ),
        ],
        bottom: TabBar(
          controller: _tabController,
          indicatorColor: Colors.white,
          indicatorWeight: 3,
          labelColor: Colors.white,
          unselectedLabelColor: Colors.white70,
          dividerColor: Colors.transparent,
          tabs: const <Widget>[
            Tab(text: 'المحادثات'),
            Tab(text: 'الحالة'),
            Tab(text: 'المكالمات'),
          ],
        ),
      ),
      body: TabBarView(
        controller: _tabController,
        children: <Widget>[
          ChatsTab(chats: _chats, onOpenChat: _openChat),
          StatusTab(statuses: _statuses),
          CallsTab(calls: _calls),
        ],
      ),
      floatingActionButton: FloatingActionButton(
        tooltip: 'محادثة جديدة',
        onPressed: () => _showDemoMessage('محادثة جديدة — تجريبي'),
        child: const Icon(Icons.chat_bubble_outline),
      ),
    );
  }
}
