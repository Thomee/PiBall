import 'dart:async';

import 'package:flutter/material.dart';
import 'package:wakelock_plus/wakelock_plus.dart';

import '../models/app_settings.dart';
import '../models/reading.dart';
import '../models/wind_layer.dart';
import '../services/cue.dart';
import '../services/orientation_service.dart';
import '../services/settings_store.dart';
import '../services/wind_calculator.dart';
import '../theme.dart';
import 'settings_screen.dart';

class HomeScreen extends StatefulWidget {
  const HomeScreen({super.key});

  @override
  State<HomeScreen> createState() => _HomeScreenState();
}

class _HomeScreenState extends State<HomeScreen> {
  AppSettings _settings = const AppSettings();
  final OrientationService _orient = OrientationService();
  StreamSubscription<AzEl>? _orientSub;

  AzEl? _live;
  double _avgAz = 0, _avgEl = 0;

  final List<Reading> _readings = [];
  List<WindLayer> _results = const [];

  bool _running = false;
  bool _starting = false;
  DateTime? _launchTime;
  DateTime? _nextRead;
  DateTime? _nextWarn;
  Timer? _ticker;

  @override
  void initState() {
    super.initState();
    _orient.start();
    _orientSub = _orient.stream.listen((azEl) {
      setState(() => _live = azEl);
    });
    SettingsStore.load().then((s) {
      setState(() {
        _settings = s;
        _orient.declinationDeg = s.declinationDeg;
      });
    });
  }

  @override
  void dispose() {
    _ticker?.cancel();
    WakelockPlus.disable();
    _orientSub?.cancel();
    _orient.dispose();
    super.dispose();
  }

  Duration get _interval => Duration(seconds: _settings.readIntervalSec);

  // --- Recording state machine -------------------------------------------

  void _start() {
    setState(() {
      _readings.clear();
      _results = const [];
      _running = true;
      _starting = true;
      _avgAz = _live?.azimuth ?? 0;
      _avgEl = _live?.elevation ?? 0;
    });
    final lead = Duration(milliseconds: _settings.preToneMs);
    final launch = DateTime.now().add(lead * 2);
    _nextRead = launch;
    _nextWarn = launch.subtract(lead);
    _ticker = Timer.periodic(const Duration(milliseconds: 100), _onTick);
    WakelockPlus.enable(); // keep the screen on through the run
  }

  void _stop() {
    _ticker?.cancel();
    _ticker = null;
    WakelockPlus.disable();
    setState(() => _running = false);
  }

  void _reset() {
    setState(() {
      _readings.clear();
      _results = const [];
      _launchTime = null;
    });
  }

  void _calculate() {
    setState(() {
      _results = computeWinds(_readings, _settings.ascentRateFtPerMin);
    });
  }

  void _onTick(Timer _) {
    final live = _live;
    if (live != null) {
      final a = _settings.averaging;
      if (a > 0 && a < 1) {
        var la = live.azimuth;
        if (_avgAz > 330 && la < 30) {
          la += 360;
        }
        if (_avgAz < 30 && la > 330) {
          la -= 360;
        }
        _avgAz = a * _avgAz + (1 - a) * la;
        if (_avgAz < 0) {
          _avgAz += 360;
        }
        if (_avgAz >= 360) {
          _avgAz -= 360;
        }
        _avgEl = a * _avgEl + (1 - a) * live.elevation;
      } else {
        _avgAz = live.azimuth;
        _avgEl = live.elevation;
      }
    }

    final now = DateTime.now();
    if (_nextWarn != null && !now.isBefore(_nextWarn!)) {
      Cue.warn();
      _nextWarn = _nextWarn!.add(_interval);
    }
    if (_nextRead != null && !now.isBefore(_nextRead!)) {
      _doRead(now);
      _nextRead = _nextRead!.add(_interval);
    }
    setState(() {});
  }

  void _doRead(DateTime now) {
    Cue.read();
    final double t;
    if (_starting) {
      _starting = false;
      _launchTime = now;
      t = 0;
      _avgAz = _live?.azimuth ?? _avgAz;
      _avgEl = _live?.elevation ?? _avgEl;
    } else {
      t = now.difference(_launchTime!).inMilliseconds / 1000.0;
    }
    _readings.add(Reading(t, _avgAz, _avgEl));
  }

  Future<void> _openSettings() async {
    final updated = await Navigator.push<AppSettings>(
      context,
      MaterialPageRoute(
        builder: (_) => SettingsScreen(settings: _settings),
      ),
    );
    if (updated == null) return;
    await SettingsStore.save(updated);
    setState(() {
      _settings = updated;
      _orient.declinationDeg = updated.declinationDeg;
    });
  }

  // --- UI -----------------------------------------------------------------

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: const Text('PiBall'),
        actions: [
          IconButton(
            onPressed: _running ? null : _openSettings,
            icon: const Icon(Icons.settings),
            tooltip: 'Settings',
          ),
        ],
      ),
      body: Padding(
        padding: const EdgeInsets.fromLTRB(16, 16, 16, 8),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            _liveCard(),
            const SizedBox(height: 12),
            _statusRow(),
            const SizedBox(height: 12),
            Expanded(
              child: ListView(
                children: [
                  if (_readings.isNotEmpty || _running) _observationsCard(),
                  if (_results.isNotEmpty) ...[
                    const SizedBox(height: 12),
                    _resultsCard(),
                  ],
                ],
              ),
            ),
          ],
        ),
      ),
      bottomNavigationBar: _bottomBar(),
    );
  }

  Widget _liveCard() {
    final live = _live;
    return Card(
      child: Padding(
        padding: const EdgeInsets.symmetric(vertical: 20, horizontal: 16),
        child: Row(
          children: [
            Expanded(
              child: _bigReadout(
                'AZIMUTH',
                live == null ? '—' : live.azimuth.toStringAsFixed(0),
                '°T',
              ),
            ),
            Container(
              width: 1,
              height: 64,
              color: Theme.of(context).dividerColor,
            ),
            Expanded(
              child: _bigReadout(
                'ELEVATION',
                live == null ? '—' : live.elevation.toStringAsFixed(0),
                '°',
              ),
            ),
          ],
        ),
      ),
    );
  }

  Widget _bigReadout(String label, String value, String unit) {
    final scheme = Theme.of(context).colorScheme;
    return Column(
      children: [
        Text(
          label,
          style: Theme.of(context)
              .textTheme
              .labelMedium
              ?.copyWith(color: scheme.onSurfaceVariant, letterSpacing: 1.2),
        ),
        const SizedBox(height: 4),
        RichText(
          text: TextSpan(
            style: kMonoLarge.copyWith(
              fontSize: 52,
              color: scheme.onSurface,
            ),
            children: [
              TextSpan(text: value),
              TextSpan(
                text: unit,
                style: TextStyle(fontSize: 22, color: scheme.onSurfaceVariant),
              ),
            ],
          ),
        ),
      ],
    );
  }

  Widget _statusRow() {
    final String label;
    final IconData icon;
    if (_running) {
      final secs = _nextRead == null
          ? 0
          : _nextRead!.difference(DateTime.now()).inMilliseconds / 1000.0;
      final next = secs > 0 ? secs.ceil() : 0;
      label = _starting
          ? 'Aim at the balloon — launch in ${next}s'
          : 'Recording · next sighting in ${next}s';
      icon = Icons.fiber_manual_record;
    } else if (_readings.isEmpty) {
      label = 'Ready';
      icon = Icons.adjust;
    } else {
      label = '${_readings.length} sightings captured';
      icon = Icons.check_circle_outline;
    }
    final scheme = Theme.of(context).colorScheme;
    return Row(
      children: [
        Icon(icon, size: 18, color: _running ? scheme.error : scheme.primary),
        const SizedBox(width: 8),
        Expanded(child: Text(label, style: Theme.of(context).textTheme.titleSmall)),
      ],
    );
  }

  Widget _observationsCard() {
    return Card(
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            Text('Observations',
                style: Theme.of(context).textTheme.titleMedium),
            const SizedBox(height: 8),
            _obsHeader(),
            const Divider(),
            for (var i = 0; i < _readings.length; i++)
              _obsRow(
                '${i + 1}',
                _readings[i].time.toStringAsFixed(0),
                _readings[i].az.toStringAsFixed(1),
                _readings[i].el.toStringAsFixed(1),
              ),
            if (_running)
              _obsRow(
                '•',
                _starting ? '—' : '…',
                _avgAz.toStringAsFixed(1),
                _avgEl.toStringAsFixed(1),
                faded: true,
              ),
          ],
        ),
      ),
    );
  }

  Widget _obsHeader() {
    final style = Theme.of(context)
        .textTheme
        .labelSmall
        ?.copyWith(color: Theme.of(context).colorScheme.onSurfaceVariant);
    return Row(
      children: [
        Expanded(flex: 2, child: Text('#', style: style)),
        Expanded(flex: 3, child: Text('TIME (s)', style: style)),
        Expanded(flex: 3, child: Text('AZ (°T)', style: style)),
        Expanded(flex: 3, child: Text('EL (°)', style: style)),
      ],
    );
  }

  Widget _obsRow(String n, String t, String az, String el,
      {bool faded = false}) {
    final base = kMonoLarge.copyWith(fontSize: 15, fontWeight: FontWeight.w500);
    final style = faded
        ? base.copyWith(color: Theme.of(context).colorScheme.onSurfaceVariant)
        : base;
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 4),
      child: Row(
        children: [
          Expanded(flex: 2, child: Text(n, style: style)),
          Expanded(flex: 3, child: Text(t, style: style)),
          Expanded(flex: 3, child: Text(az, style: style)),
          Expanded(flex: 3, child: Text(el, style: style)),
        ],
      ),
    );
  }

  Widget _resultsCard() {
    final scheme = Theme.of(context).colorScheme;
    return Card(
      color: scheme.primaryContainer,
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            Text('Winds aloft',
                style: Theme.of(context).textTheme.titleMedium?.copyWith(
                      color: scheme.onPrimaryContainer,
                    )),
            const SizedBox(height: 8),
            Row(
              children: [
                Expanded(child: _resHead('HEIGHT')),
                Expanded(child: _resHead('FROM')),
                Expanded(child: _resHead('SPEED')),
              ],
            ),
            Divider(color: scheme.onPrimaryContainer.withValues(alpha: 0.3)),
            for (final w in _results)
              Padding(
                padding: const EdgeInsets.symmetric(vertical: 5),
                child: Row(
                  children: [
                    Expanded(child: _resCell('${w.heightFt.toStringAsFixed(0)} ft')),
                    Expanded(child: _resCell('${w.headingDeg.toStringAsFixed(0)}°')),
                    Expanded(child: _resCell('${w.speedKts.toStringAsFixed(0)} kt')),
                  ],
                ),
              ),
          ],
        ),
      ),
    );
  }

  Widget _resHead(String t) => Text(
        t,
        style: Theme.of(context).textTheme.labelSmall?.copyWith(
              color: Theme.of(context)
                  .colorScheme
                  .onPrimaryContainer
                  .withValues(alpha: 0.8),
              letterSpacing: 1.1,
            ),
      );

  Widget _resCell(String t) => Text(
        t,
        style: kMonoLarge.copyWith(
          fontSize: 16,
          color: Theme.of(context).colorScheme.onPrimaryContainer,
        ),
      );

  Widget _bottomBar() {
    final String label;
    final VoidCallback onTap;
    final bool danger;
    if (_running) {
      label = 'Stop';
      onTap = _stop;
      danger = true;
    } else if (_readings.isEmpty) {
      label = 'Start';
      onTap = _start;
      danger = false;
    } else {
      label = 'Calculate';
      onTap = _calculate;
      danger = false;
    }

    final scheme = Theme.of(context).colorScheme;
    return SafeArea(
      child: Padding(
        padding: const EdgeInsets.fromLTRB(16, 4, 16, 12),
        child: Row(
          children: [
            Expanded(
              child: FilledButton(
                onPressed: onTap,
                style: danger
                    ? FilledButton.styleFrom(
                        backgroundColor: scheme.error,
                        foregroundColor: scheme.onError,
                      )
                    : null,
                child: Text(label),
              ),
            ),
            if (!_running && _readings.isNotEmpty) ...[
              const SizedBox(width: 12),
              OutlinedButton(
                onPressed: _reset,
                style: OutlinedButton.styleFrom(
                  minimumSize: const Size(0, 60),
                ),
                child: const Text('Reset'),
              ),
            ],
          ],
        ),
      ),
    );
  }
}
