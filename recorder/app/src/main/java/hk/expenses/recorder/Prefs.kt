package hk.expenses.recorder

import android.content.Context

object Prefs {
    private fun p(ctx: Context) = ctx.getSharedPreferences("prefs", Context.MODE_PRIVATE)

    /** Post a confirmation notification for each tracked expense (testing aid). */
    fun confirmTracked(ctx: Context): Boolean = p(ctx).getBoolean("confirm_tracked", true)

    fun setConfirmTracked(ctx: Context, v: Boolean) {
        p(ctx).edit().putBoolean("confirm_tracked", v).apply()
    }
}
