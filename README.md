# komado

Android端末からMacのデスクトップや個別のウインドウを操作する、ネイティブのリモートデスクトップアプリケーションです。到達可能なネットワークで接続し、映像と操作イベントをWebRTCで転送します。

ペアリング、画面・アプリ・ウインドウの表示と操作、音声転送、文字入力、クリップボード共有、自動再接続などを初期実装しています。MacとAndroidエミュレーターを接続し、テスト用アプリの表示や操作を確認しています。

Android実機、別端末間のネットワーク、一般アプリや画面ロック時の動作は未検証です。検証範囲と制約は[互換性](docs/compatibility.md)を参照してください。

## ドキュメント

- [開発計画書](docs/development-plan.md)：要件、技術スタック、開発フェーズ、性能目標
- [アーキテクチャ](docs/architecture.md)：構成と主要な技術的課題
- [通信プロトコル](docs/protocol.md)：接続フローと通信仕様の検討事項
- [セキュリティ](docs/security.md)：認証、通信経路、入力制御の要件
- [開発ガイド](docs/development.md)：ビルド・テストと操作手順
- [テスト計画](docs/testing.md)：自動テスト、実機・ネットワーク検証
- [互換性](docs/compatibility.md)：対応候補と検証状況
- [技術選定の記録](docs/decisions/README.md)：ADRの運用
- [プロトコル資産](protocol/README.md)：スキーマと共通テストデータの管理方針
- [AGENTS.md](AGENTS.md)：開発エージェント向けの方針
