# 公開の進め方

ローカル版は本人が完成を確認済み。次は、GitHubでコードを公開し、VercelとNeonの無料プランで友人がアクセスできるURLを用意する。公開完了は、実際のURLで会話を確認してから判断する。

## 現在の状態（2026年10月18日）

- [GitHubへのコード公開](https://github.com/zahin-dev/translate-chat)：完了。初回の記録は完成したローカル版と公開準備のコードを含む。
- [GitHub Actions](https://github.com/zahin-dev/translate-chat/actions/runs/39104069979)：JUnit 14件・JavaScriptテスト3件・フロントエンド構文・Dockerコンテナのビルドが成功。
- [Vercelプロジェクト](https://vercel.com/zahin-dev1/translate-chat)：作成済み。対象アカウントがHobbyであることをダッシュボードでも確認。既存の別アプリは変更していない。
- Neon：Vercelダッシュボードの「Install Integration」で規約同意待ち。接続ツールはDB連携一覧の取得に権限がなく、ダッシュボード経由で準備する。利用規約への同意とアカウント情報の共有は本人が確認する。
- 公開用環境変数・クラウドDBへの初期化・アプリのデプロイ・公開URLでの確認：未完了。アプリがクラウドで動いたとはまだ扱わない。

## 選んだ構成

| 役割 | 候補・方針 | 理由と制約 |
|---|---|---|
| コード管理 | GitHub | 変更履歴と、自動テストの結果を残す。 |
| アプリの公開 | Vercel Hobby + Container Runtime | Java / Spring Bootと画面をまとめて動かす。Container Runtimeは全プランで利用できるベータ機能。利用アカウントでの動作確認が必要。 |
| 会話履歴の保存 | Neon FreeのPostgreSQL | カード不要・期限なしの無料プラン。容量・実行時間の上限がある。 |

最新であることだけでなく、現在のJavaアプリを維持でき、無料で管理する項目が少ないことを優先した。Vercelのコンテナが利用できない場合は、同じDockerファイルをRenderに配置する案へ戻れる。Renderの無料アプリは15分未使用で休止する。Renderの無料PostgreSQLには30日の期限があるため、継続保存先には採用しない。

料金条件は2026年10月18日に公式情報で確認。有料プラン・独自ドメインは使わず、カード登録や課金が必要な画面が出た場合はそこで条件を再確認する。

## 公開するファイル

- アプリのソース、テスト、起動スクリプト、要件・設計、公開手順。
- `.github/workflows/ci.yml`：実際のPostgreSQLを使うテストとコンテナのビルド。
- `Dockerfile.vercel`：Java 21でアプリをビルドして実行するための定義。
- `vercel.json`：コンテナを使うプロジェクトの最小設定。
- `.env.example`：必要な設定名の例。実際のパスワードは書かない。

`.local/`の会話履歴・パスワード、`.tools/`、ビルド結果、`.env*`、Vercelのローカル接続情報はGit管理から除外する。ローカルの履歴はクラウドDBへ自動移行せず、公開先は新しい保存先として準備する。

## 公開の順番

1. GitHubへコードを公開し、自動テストとコンテナのビルドを通す。
2. Vercelの対象アカウントで、このアプリ専用の新規プロジェクトを作成する。既存の別アプリは変更しない。
3. そのプロジェクトにNeon Freeを用意し、公開用の接続情報を設定する。ProductionとPreviewのDBは分ける。
4. コンテナ用の環境変数を設定してデプロイする。
5. 実際のHTTPS URLで作成・参加・翻訳・原文・退出・再入室を確認する。

GitHub Actionsはテスト専用DBと仮パスワードで動く。公開用DBの秘密情報は渡さない。ローカルのDockerエンジンが停止している場合、コンテナの実ビルドはGitHub Actions側で確認する。Docker自体の新規インストールは不要。

## Vercelで設定する値

| 名前 | 値 |
|---|---|
| `CHAT_DB_URL` | NeonのJava用接続URL。`jdbc:postgresql://`で始まる。TLS接続を使い、ユーザー名・パスワードはURLに含めず次の設定に分ける。 |
| `CHAT_DB_USER` | 公開用DBのユーザー名。 |
| `CHAT_DB_PASSWORD` | 公開用DBのパスワード。Secretとして設定し、チャットやGitHubに貼らない。 |
| `CHAT_PUBLIC_ORIGIN` | 実際に利用する公開URLのオリジン。例：`https://実際のホスト名`。パスは含めない。ProductionとPreviewでそれぞれ設定する。 |

`SPRING_PROFILES_ACTIVE=cloud` と `PORT=8080` はコンテナの定義に含む。`.env.example`をコピーしただけではSpring Bootは読み込まない。Vercelの環境変数として設定する。

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
- [Renderの無料枠](https://render.com/docs/free)
