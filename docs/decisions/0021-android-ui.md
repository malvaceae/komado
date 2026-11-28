# ADR 0021: Androidの画面

- 状態: 採用
- 日付: 2026-11-28

## 背景と決定

元の開発計画に従い、Androidの画面をJetpack ComposeとMaterial 3で実装する。従来のViewsで作られたホーム画面から順に移行し、ペアリング、映像、入力など既存の機能を維持する。WebRTCの映像描画やIMEなど、プラットフォームのViewが必要な箇所はComposeと連携する。

Compose BOMは2026.11.01、コンパイラーはKotlin 2.4.21に固定する。必要なビルド用SDKは37.2で、最低対応OSはAndroid 8のままとする。未移行の画面には従来のViewsが残る。
