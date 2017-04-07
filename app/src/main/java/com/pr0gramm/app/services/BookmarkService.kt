package com.pr0gramm.app.services

import android.app.Application
import android.database.sqlite.SQLiteDatabase
import com.pr0gramm.app.feed.FeedFilter
import com.pr0gramm.app.orm.Bookmark
import com.pr0gramm.app.orm.Bookmark.byFilter
import com.pr0gramm.app.util.AndroidUtility.checkNotMainThread
import com.pr0gramm.app.util.AndroidUtility.doInBackground
import com.pr0gramm.app.util.BackgroundScheduler
import com.pr0gramm.app.util.Holder
import com.pr0gramm.app.util.ifAbsent
import rx.Completable
import rx.Observable
import rx.Single
import rx.subjects.BehaviorSubject
import javax.inject.Inject
import javax.inject.Singleton

/**
 */
@Singleton
class BookmarkService @Inject constructor(
        private val context: Application,
        private val database: Holder<SQLiteDatabase>) {

    private val onChange = BehaviorSubject.create<Void>(null as Void?).toSerialized()

    /**
     * Creates a bookmark for the filter.

     * @param filter The filter to create a bookmark for.
     */
    fun create(filter: FeedFilter, title: String): Completable {
        return doInBackground {
            // check if here is an existing item
            byFilter(database.value(), filter).ifAbsent {
                // create new entry
                Bookmark.save(database.value(), Bookmark.of(filter, title))
                triggerChange()
            }
        }
    }

    /**
     * Returns an observable producing "true", if the item is bookmarkable.
     * The observable produces "false" otherwise.

     * @param filter The filter that the user wants to bookmark.
     */
    fun isBookmarkable(filter: FeedFilter): Single<Boolean> {
        if (filter.isBasic)
            return Single.just(false)

        if (filter.likes.isPresent)
            return Single.just(false)

        // check if already in database
        return database.asSingle().map { db -> !Bookmark.byFilter(db, filter).isPresent }
    }

    private fun triggerChange() {
        onChange.onNext(null)
    }

    fun get(): Observable<List<Bookmark>> {
        return onChange.subscribeOn(BackgroundScheduler.instance()).map { list() }
    }

    /**
     * Blockingly list the bookmarks, ordered by title.

     * @return The current bookmarks
     */
    private fun list(): List<Bookmark> {
        checkNotMainThread()
        return Bookmark.all(database.value())
    }

    /**
     * Delete the bookmark. This method will not block.

     * @param bookmark The bookmark that is to be deleted.
     */
    fun delete(bookmark: Bookmark): Completable {
        return doInBackground {
            Bookmark.delete(database.value(), bookmark)
            triggerChange()
        }
    }
}