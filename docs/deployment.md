# トークリア 公開手順

ローカル版の実装とGitHubへのコード公開は完了。クラウド版はVercelとNeonの無料プランで準備中。本書は、公開先の構成・必要な設定・公開までの残作業をまとめる。

## 現在の状態（2026年10月18日）

- [GitHubへのコード公開](https://github.com/zahin-dev/translate-chat)：完了。
- [GitHub Actions](https://github.com/zahin-dev/translate-chat/actions/workflows/ci.yml)：JUnit・JavaScript・フロントエンド構文・Dockerコンテナのビルドと会話・保存の検証を実行。検証範囲と結果は [README](../README.md#テスト動作確認) を参照。
- Dockerでのローカル実行：アプリとPostgreSQLの起動、複数参加者の会話、不正な通信元の拒否、コンテナ再作成後の履歴保持を確認済み。Chromeで双方向の翻訳も確認済み。
- Vercel：Hobbyプランのプロジェクトを作成済み。
- Neon：未接続。無料DBの作成・接続情報の設定が残る。
- 公開用環境変数・クラウドDBの初期化・アプリのデプロイ・公開URLでの確認：未完了。

## 選んだ構成

ローカルでは `compose.yaml` でアプリとPostgreSQLを起動し、公開用と同じ `Dockerfile.vercel` を利用する。ローカルDBはDockerの名前付きボリュームに保存し、クラウドはNeonを使う。Composeはローカル用のためVercelへは配置しない。起動・停止・検証は [README](../README.md) を参照。

| 役割 | 候補・方針 | 理由と制約 |
|---|---|---|
| コード管理 | GitHub | 変更履歴と、自動テストの結果を残す。 |
| アプリの公開 | Vercel Hobby + Container Runtime | Java / Spring Bootと画面をまとめて動かす。Container Runtimeは全プランで利用できるベータ機能。利用アカウントでの動作確認が必要。 |
| 会話履歴の保存 | Neon FreeのPostgreSQL | カード不要・期限なしの無料プラン。容量・実行時間の上限がある。 |

現在のJavaアプリを維持し、アプリの実行とDBの管理をそれぞれのサービスに任せる構成。Vercelでのコンテナ実行は、デプロイ時の動作確認が残る。

料金条件は2026年10月18日に公式情報で確認。有料プラン・独自ドメインは使わず、カード登録や課金が必要な画面が出た場合はそこで条件を再確認する。

## 公開するファイル

- アプリのソース、テスト、起動スクリプト、要件・設計、公開手順。
- `.github/workflows/ci.yml`：実際のPostgreSQLを使うテストとコンテナのビルド。
- `Dockerfile.vercel`：Java 21でアプリをビルドして実行するための定義。
- `vercel.json`：コンテナを使うプロジェクトの最小設定。
- `.env.example`：必要な設定名の例。実際のパスワードは書かない。

`.local/`の会話履歴・パスワード、`.tools/`、ビルド結果、`.env*`（記入例の `.env.example` を除く）、Vercelのローカル接続情報はGit管理から除外する。ローカルの履歴はクラウドDBへ自動移行せず、公開先は新しい保存先として準備する。

## 公開の順番

1. GitHubへコードを公開し、自動テストとコンテナのビルドを通す（完了）。
2. Vercelで、このアプリ専用のプロジェクトを作成する（完了）。
3. そのプロジェクトにNeon Freeを用意し、公開用の接続情報を設定する。ProductionとPreviewのDBは分ける。
4. コンテナ用の環境変数を設定してデプロイする。
5. 実際のHTTPS URLで作成・参加・翻訳・原文・退出・再入室を確認する。

GitHub Actionsはテスト専用DBとテスト用パスワードで動く。公開用DBの秘密情報は渡さない。ローカルでコンテナのビルド・検証を行う際は、Docker Desktopを起動する。

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
