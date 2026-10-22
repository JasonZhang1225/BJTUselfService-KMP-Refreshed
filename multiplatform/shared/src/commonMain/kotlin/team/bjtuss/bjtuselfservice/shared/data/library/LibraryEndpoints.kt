package team.bjtuss.bjtuselfservice.shared.data.library

/**
 * 图书馆（opac.bjtu.edu.cn）协议常量。
 *
 * 登录不是普通的账号密码表单：图书馆把 SSO 授权码交给一个服务端渲染的回调页，
 * 回调页再用页面内生成的 `data/time/enc` 调 `/OAuth2/bjtu/login` 建立会话。
 * 详见 [LibraryLoginProtocol]。
 */
object LibraryEndpoints {
    const val ORIGIN = "https://opac.bjtu.edu.cn"
    const val SSO_ORIGIN = "https://sso.bjtu.edu.cn"

    /** SSO 授权链入口：图书馆自己的 init 页会再跳一次到 SSO。 */
    const val OAUTH_INIT = "$ORIGIN/OAuth2/bjtu/init"
    const val OAUTH_CALLBACK = "$ORIGIN/OAuth2/bjtu/index"
    const val OAUTH_LOGIN = "$ORIGIN/OAuth2/bjtu/login"
    const val OAUTH_CLIENT_ID = "sAoCCq6lsYshsK4q6seRUymPdWEFMqQ6lE3ASwel"

    /** 登录完成后回调页会把浏览器带回这个地址（附带 data/time/enc）。 */
    const val LOGIN_REFER = "$ORIGIN/find/sso/login/bjtu/0"
    val OAUTH_INIT_URL: String = "$OAUTH_INIT?refer=$LOGIN_REFER"
    val SSO_AUTHORIZE_URL: String =
        "$SSO_ORIGIN/o/authorize/?response_type=code&client_id=$OAUTH_CLIENT_ID&redirect_uri=$OAUTH_CALLBACK"
    const val SSO_LOGIN_URL = "$SSO_ORIGIN/sso/login"

    const val USER_INFO = "$ORIGIN/find/userInfo/getUserInfo"
    const val LOAN_LIST = "$ORIGIN/find/loanInfo/loanList"
    const val LOAN_HISTORY = "$ORIGIN/find/loanInfo/loanHistoryList"
    const val RENEW = "$ORIGIN/find/lendbook/reNew"

    /** API 一律同源 JSON POST/GET，依赖登录后写入 Cookie 会话。 */
    const val API_JSON_CONTENT_TYPE = "application/json;charset=UTF-8"
}
