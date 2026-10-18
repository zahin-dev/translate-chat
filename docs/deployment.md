# トークリア 公開手順

公開URL：**[https://talklear-zahin-dev.vercel.app/](https://talklear-zahin-dev.vercel.app/)**

Vercel HobbyとNeon Freeで公開。本書は、公開先の構成・設定・再デプロイ時の確認項目をまとめる。

## 現在の状態（2026年10月18日）

- [GitHubへのコード公開](https://github.com/zahin-dev/translate-chat)：完了。
- [GitHub Actions](https://github.com/zahin-dev/translate-chat/actions/workflows/ci.yml)：JUnit・JavaScript・フロントエンド構文・Dockerコンテナのビルドと会話・保存の検証を実行。検証範囲と結果は [README](../README.md#テスト動作確認) を参照。
- Dockerでのローカル実行：アプリとPostgreSQLの起動、複数参加者の会話、不正な通信元の拒否、コンテナ再作成後の履歴保持を確認済み。Chromeで双方向の翻訳も確認済み。
- Vercel：HobbyプランでSpring Bootのコンテナを公開済み。GitHubの `main` に接続。
- Neon：Freeプランの `talklear-db` を作成し、本番環境へ接続済み。プレビュー・開発環境へ本番DBの接続情報を配布しない。
- 公開URL：Vercelへのログインやアプリのアカウント登録は不要。プレビュー環境はVercel認証で保護する。
- 動作確認：認証情報・CookieなしのHTTP検証で、部屋作成・3人参加・送信・不正な送信元の拒否・退出・再入室・履歴取得を確認。Chromeで日英双方向翻訳・原文表示を確認。

## 選んだ構成

ローカルでは `compose.yaml` でアプリとPostgreSQLを起動し、公開用と同じ `Dockerfile.vercel` を利用する。ローカルDBはDockerの名前付きボリュームに保存し、クラウドはNeonを使う。Composeはローカル用のためVercelへは配置しない。起動・停止・検証は [README](../README.md) を参照。

| 役割 | 使用サービス | 理由と制約 |
|---|---|---|
| コード管理 | GitHub | 変更履歴と、自動テストの結果を残す。 |
| アプリの公開 | Vercel Hobby + Container Runtime | Java / Spring Bootと画面をまとめて動かす。Container Runtimeはベータ機能。 |
| 会話履歴の保存 | Neon FreeのPostgreSQL | カード不要・期限なしの無料プラン。容量・実行時間の上限がある。 |

Javaアプリを維持し、アプリの実行とDBの管理をそれぞれのサービスに任せる構成。コンテナやDBが休止している場合、最初のアクセスに時間がかかることがある。

料金条件は2026年10月18日に公式情報で確認。有料プラン・独自ドメインは使わず、カード登録や課金が必要な画面が出た場合はそこで条件を再確認する。

## 公開するファイル

- アプリのソース、テスト、起動スクリプト、要件・設計、公開手順。
- `.github/workflows/ci.yml`：実際のPostgreSQLを使うテストとコンテナのビルド。
- `Dockerfile.vercel`：Java 21でアプリをビルドして実行するための定義。
- `vercel.json`：コンテナを使うプロジェクトの最小設定。
- `.env.example`：必要な設定名の例。実際のパスワードは書かない。

`.local/`の会話履歴・パスワード、`.tools/`、ビルド結果、`.env*`（記入例の `.env.example` を除く）、Vercelのローカル接続情報はGit管理から除外する。ローカルの履歴はクラウドDBへ自動移行せず、公開先は新しい保存先として準備する。

## 公開・更新の流れ

1. 初回のみ、VercelプロジェクトとNeon Freeを作成し、本番環境へ接続する。
2. GitHubの `main` にコードを反映する。GitHub Actionsで自動検証し、Vercelでコンテナをビルド・デプロイする。
3. 実際の公開URLで作成・参加・翻訳・原文・退出・再入室を確認する。
4. 再デプロイ後も会話履歴を取得できることを確認する。

プレビュー環境を利用する場合は、別のDBまたはNeonの別ブランチを接続し、公開元のURLも環境に合わせて設定する。

GitHub Actionsはテスト専用DBとテスト用パスワードで動く。公開用DBの秘密情報は渡さない。ローカルでコンテナのビルド・検証を行う際は、Docker Desktopを起動する。

## Vercelで設定する値

Neon連携を本番環境に接続すると、次の値が秘密情報として自動登録される。アプリはこれらからJDBC接続を構成するため、パスワードのコピーや手入力は不要。

| 名前 | 値 |
|---|---|
| `PGHOST` | 接続プールを利用するNeonのホスト名。 |
| `PGDATABASE` | DB名。 |
| `PGUSER` | DBユーザー名。 |
| `PGPASSWORD` | DBパスワード。値をチャットやGitHubへ貼らない。 |
| `CHAT_PUBLIC_ORIGIN` | 手動で設定する公開元。`https://talklear-zahin-dev.vercel.app`。パスは含めない。 |

Neonへの接続では、TLSの証明書とホスト名をJavaの信頼ストアを使って検証する。別のDBへ手動接続する場合は、`CHAT_DB_URL`・`CHAT_DB_USER`・`CHAT_DB_PASSWORD` を設定すると `PG*` より優先される。記入例は [.env.example](../.env.example) を参照。

`SPRING_PROFILES_ACTIVE=cloud` と `PORT=8080` はコンテナの定義に含む。`vercel.json` のフレームワークは `container` を指定する。`null`（Other）にするとコンテナのビルドが実行されないため指定しない。`.env.example`をコピーしただけではSpring Bootは読み込まない。

公開URLが複数ある場合も、アプリ利用時は`CHAT_PUBLIC_ORIGIN`に設定したURLに統一する。別のオリジンからの送信は拒否する。HTTPSがVercelで終端される場合も、設定済みの公開オリジンを基準に確認し、クライアントが送った転送ヘッダーだけで許可しない。

## 公開時の確認

- アプリが起動し、ホーム画面を表示できる。
- 別々のブラウザで同じ部屋に入り、双方の送信が届く。
- 日英の翻訳と原文表示が動く。翻訳が利用できない場合も原文で続けられる。
- 再入室・アプリ再デプロイ後も会話履歴が残る。
- 無料枠と利用状況を確認する。常時アクセスや無制限利用の保証とは扱わない。

## 公式資料

- [Vercel Container Runtime](https://vercel.com/docs/functions/runtimes/container)
- [Vercel Hobby](https://vercel.com/docs/plans/hobby)
- [Neonの料金](https://neon.com/pricing)
- [NeonとJavaの接続](https://neon.com/docs/guides/java)
